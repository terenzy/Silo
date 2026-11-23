package dev.silo.app.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.silo.app.SiloApp
import dev.silo.app.ui.i18n.SiloStrings
import dev.silo.app.ui.i18n.resolveStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SensorsOffTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inFlight = AtomicBoolean(false)

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
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!inFlight.compareAndSet(false, true)) return
        val repo = (application as SiloApp).repository
        serviceScope.launch {
            var delegatedToUnlock = false
            try {
                val status = withContext(Dispatchers.IO) { repo.getStatus().getOrNull() }
                if (status == null) {
                    updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileOffline)
                    return@launch
                }
                val currentlyMuted = status.sensorsOffActive || status.micMuted || status.cameraMuted
                if (currentlyMuted) {
                    // Unmuting Mic/Camera weakens physical privacy: require unlock when locked (APP-H01)
                    if (isLocked) {
                        delegatedToUnlock = true
                        inFlight.set(false)
                        unlockAndRun {
                            if (!inFlight.compareAndSet(false, true)) return@unlockAndRun
                            serviceScope.launch {
                                try {
                                    if (isLocked) return@launch
                                    applySensorsOff(enabled = false)
                                } finally {
                                    inFlight.set(false)
                                }
                            }
                        }
                        return@launch
                    }
                    applySensorsOff(enabled = false)
                } else {
                    // Muting Mic+Camera is allowed immediately on lockscreen
                    applySensorsOff(enabled = true)
                }
            } finally {
                if (!delegatedToUnlock) {
                    inFlight.set(false)
                }
            }
        }
    }

    private suspend fun applySensorsOff(enabled: Boolean) {
        val repo = (application as SiloApp).repository
        val s = tileStrings()
        val res = withContext(Dispatchers.IO) { repo.setSensorsOff(enabled) }
        res.onSuccess { live ->
            applySensorStateToTile(live.micMuted, live.cameraMuted, s)
        }.onFailure {
            updateTileOnMain(Tile.STATE_UNAVAILABLE, s.tileError)
        }
    }

    private fun applySensorStateToTile(micMuted: Boolean, cameraMuted: Boolean, s: SiloStrings) {
        when {
            micMuted && cameraMuted -> updateTileOnMain(Tile.STATE_ACTIVE, s.tileSensorsMuted)
            micMuted -> updateTileOnMain(Tile.STATE_ACTIVE, s.tileMicMuted)
            cameraMuted -> updateTileOnMain(Tile.STATE_ACTIVE, s.tileCamMuted)
            else -> updateTileOnMain(Tile.STATE_INACTIVE, s.tileSensorsLive)
        }
    }

    private fun refreshTile() {
        serviceScope.launch {
            val repo = (application as SiloApp).repository
            val s = tileStrings()
            val status = withContext(Dispatchers.IO) { repo.getStatus().getOrNull() }
            if (status == null) {
                updateTileOnMain(Tile.STATE_UNAVAILABLE, s.tileOffline)
            } else if (status.micMuted || status.cameraMuted) {
                applySensorStateToTile(status.micMuted, status.cameraMuted, s)
            } else {
                applySensorStateToTile(status.sensorsOffActive, status.sensorsOffActive, s)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

