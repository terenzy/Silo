package dev.silo.app.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.silo.app.SiloApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SensorsOffTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val repo = (application as SiloApp).repository
        scope.launch {
            val status = repo.getStatus().getOrNull() ?: return@launch
            repo.setSensorsOff(!status.sensorsOffActive)
            refreshTile()
        }
    }

    private fun refreshTile() {
        val repo = (application as SiloApp).repository
        scope.launch {
            val status = repo.getStatus().getOrNull()
            val tile = qsTile ?: return@launch
            if (status == null) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Offline"
            } else if (status.sensorsOffActive) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Mic/Cam Muted"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Active"
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
