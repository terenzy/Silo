package dev.silo.app.tiles

import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.silo.app.SiloApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 2-tap confirmed Panic Reboot Quick Settings Tile.
 * First tap arms confirmation for 5 seconds; second tap triggers clean reboot to BFU
 * (Before First Unlock) so FBE encryption keys are purged from RAM.
 */
class PanicRebootTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var armedAtElapsedMs: Long = 0L

    override fun onStartListening() {
        super.onStartListening()
        resetVisual()
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        if (armedAtElapsedMs > 0L && now - armedAtElapsedMs <= 5_000L) {
            armedAtElapsedMs = 0L
            val tile = qsTile
            if (tile != null) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Rebooting..."
                tile.updateTile()
            }
            val repo = (application as SiloApp).repository
            scope.launch {
                repo.triggerPanicReboot()
            }
        } else {
            armedAtElapsedMs = now
            val tile = qsTile
            if (tile != null) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Tap again (5s)"
                tile.updateTile()
            }
            scope.launch {
                delay(5_200L)
                if (SystemClock.elapsedRealtime() - armedAtElapsedMs >= 5_000L) {
                    armedAtElapsedMs = 0L
                    resetVisual()
                }
            }
        }
    }

    private fun resetVisual() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = "Tap 2x -> BFU"
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
