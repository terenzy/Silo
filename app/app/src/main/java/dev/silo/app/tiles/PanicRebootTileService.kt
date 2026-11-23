package dev.silo.app.tiles

import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.silo.app.SiloApp
import dev.silo.app.ui.i18n.SiloStrings
import dev.silo.app.ui.i18n.resolveStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2-tap confirmed Panic Reboot Quick Settings Tile.
 * First tap arms confirmation for 5 seconds; second tap triggers clean reboot to BFU
 * (Before First Unlock) so FBE encryption keys are purged from RAM.
 */
class PanicRebootTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var armedAtElapsedMs: Long = 0L

    @Volatile
    private var rebooting: Boolean = false

    private var disarmJob: Job? = null

    private fun tileStrings(): SiloStrings {
        val repo = (application as SiloApp).repository
        return resolveStrings(repo.getLanguagePref(applicationContext))
    }

    private fun updateTileOnMain(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.subtitle = subtitle
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!rebooting) {
            disarmJob?.cancel()
            disarmJob = null
            armedAtElapsedMs = 0L
            resetVisual()
        }
    }

    override fun onClick() {
        super.onClick()
        if (rebooting) return
        val s = tileStrings()
        val now = SystemClock.elapsedRealtime()
        if (armedAtElapsedMs > 0L && now - armedAtElapsedMs <= 5_000L) {
            disarmJob?.cancel()
            disarmJob = null
            armedAtElapsedMs = 0L
            rebooting = true
            updateTileOnMain(Tile.STATE_ACTIVE, s.tileRebooting)

            val repo = (application as SiloApp).repository
            serviceScope.launch {
                val res = withContext(Dispatchers.IO) { repo.triggerPanicReboot() }
                rebooting = false
                if (res.isFailure) {
                    updateTileOnMain(Tile.STATE_INACTIVE, tileStrings().tileRebootFailed)
                    disarmJob?.cancel()
                    disarmJob = serviceScope.launch {
                        delay(3_000L)
                        if (!rebooting && armedAtElapsedMs == 0L) {
                            resetVisual()
                        }
                    }
                }
            }
        } else {
            disarmJob?.cancel()
            armedAtElapsedMs = now
            updateTileOnMain(Tile.STATE_ACTIVE, s.tileTapAgain5s)
            disarmJob = serviceScope.launch {
                delay(5_200L)
                if (!rebooting && armedAtElapsedMs == now) {
                    armedAtElapsedMs = 0L
                    resetVisual()
                }
            }
        }
    }

    private fun resetVisual() {
        updateTileOnMain(Tile.STATE_INACTIVE, tileStrings().tileTap2xBfu)
    }

    override fun onDestroy() {
        disarmJob?.cancel()
        disarmJob = null
        serviceScope.cancel()
        super.onDestroy()
    }
}

