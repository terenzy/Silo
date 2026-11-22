package dev.silo.app.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.silo.app.SiloApp
import dev.silo.app.ipc.FirewallMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LockdownTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val repo = (application as SiloApp).repository
        scope.launch {
            val cfg = repo.getConfig().getOrNull() ?: return@launch
            val currentlyLockdown = cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY
            val updated = if (currentlyLockdown) {
                cfg.firewall.copy(mode = FirewallMode.DEFAULT_ALLOW)
            } else {
                cfg.firewall.copy(enabled = true, mode = FirewallMode.DEFAULT_DENY)
            }
            repo.setFirewallConfig(updated)
            refreshTile()
        }
    }

    private fun refreshTile() {
        val repo = (application as SiloApp).repository
        scope.launch {
            val cfg = repo.getConfig().getOrNull()
            val tile = qsTile ?: return@launch
            if (cfg == null) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Offline"
            } else if (cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Default-Deny"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = if (cfg.firewall.enabled) "Default-Allow" else "Off"
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
