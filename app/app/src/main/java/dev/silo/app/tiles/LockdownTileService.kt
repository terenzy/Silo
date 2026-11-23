package dev.silo.app.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.silo.app.SiloApp
import dev.silo.app.ipc.FirewallMode
import dev.silo.app.ui.i18n.SiloStrings
import dev.silo.app.ui.i18n.resolveStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class LockdownTileService : TileService() {
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
                val cfg = withContext(Dispatchers.IO) { repo.getConfig().getOrNull() }
                if (cfg == null) {
                    updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileOffline)
                    return@launch
                }
                val currentlyLockdown =
                    (cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY) ||
                        cfg.firewall.panicLockdownActive

                if (currentlyLockdown) {
                    // Disabling Lockdown weakens device security: require unlock when locked (APP-H01)
                    if (isLocked) {
                        delegatedToUnlock = true
                        inFlight.set(false)
                        unlockAndRun {
                            if (!inFlight.compareAndSet(false, true)) return@unlockAndRun
                            serviceScope.launch {
                                try {
                                    if (isLocked) return@launch
                                    applyDisableLockdown()
                                } finally {
                                    inFlight.set(false)
                                }
                            }
                        }
                        return@launch
                    }
                    applyDisableLockdown()
                } else {
                    // Enabling Lockdown is allowed immediately even on lockscreen
                    val updated = cfg.firewall.copy(
                        enabled = true,
                        mode = FirewallMode.DEFAULT_DENY,
                    )
                    val res = withContext(Dispatchers.IO) { repo.setFirewallConfig(updated) }
                    if (res.isFailure) {
                        updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileError)
                    } else {
                        refreshTileInternal()
                    }
                }
            } finally {
                if (!delegatedToUnlock) {
                    inFlight.set(false)
                }
            }
        }
    }

    private suspend fun applyDisableLockdown() {
        val repo = (application as SiloApp).repository
        val fresh = withContext(Dispatchers.IO) { repo.getConfig().getOrNull() }
        if (fresh == null) {
            updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileOffline)
            return
        }
        val updated = fresh.firewall.copy(
            mode = FirewallMode.DEFAULT_ALLOW,
            panicLockdownActive = false,
        )
        val res = withContext(Dispatchers.IO) { repo.setFirewallConfig(updated) }
        if (res.isFailure) {
            updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileError)
        } else {
            refreshTileInternal()
        }
    }

    private fun refreshTile() {
        serviceScope.launch { refreshTileInternal() }
    }

    private suspend fun refreshTileInternal() {
        val repo = (application as SiloApp).repository
        val s = tileStrings()
        val cfg = withContext(Dispatchers.IO) { repo.getConfig().getOrNull() }
        if (cfg == null) {
            updateTileOnMain(Tile.STATE_UNAVAILABLE, s.tileOffline)
        } else if (cfg.firewall.panicLockdownActive) {
            updateTileOnMain(Tile.STATE_ACTIVE, s.tilePanicLocked)
        } else if (cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY) {
            updateTileOnMain(Tile.STATE_ACTIVE, s.tileDefaultDeny)
        } else {
            updateTileOnMain(
                Tile.STATE_INACTIVE,
                if (cfg.firewall.enabled) s.tileDefaultAllow else s.tileOff,
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

