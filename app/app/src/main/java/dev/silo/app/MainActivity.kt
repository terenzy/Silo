package dev.silo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.screens.*
import dev.silo.app.ui.theme.SiloTheme

private enum class SiloTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Overview", Icons.Default.Shield),
    FIREWALL("Firewall", Icons.Default.Policy),
    ISOLATION("Silo", Icons.Default.Lock),
    MONITOR("Monitor", Icons.Default.Radar),
    HARDENING("Kernel", Icons.Default.Tune),
    SENTINEL("Audit", Icons.Default.VerifiedUser),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SiloTheme(darkTheme = true) {
                SiloRootHost()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiloRootHost(vm: SiloViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(SiloTab.DASHBOARD) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentTab) {
                            SiloTab.DASHBOARD -> "Silo"
                            SiloTab.FIREWALL -> "Kernel Firewall"
                            SiloTab.ISOLATION -> "Scopes & Freezer"
                            SiloTab.MONITOR -> "Live Monitor"
                            SiloTab.HARDENING -> "Hardening & BFU"
                            SiloTab.SENTINEL -> "Stealth Sentinel"
                        }
                    )
                },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                SiloTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentTab) {
                SiloTab.DASHBOARD -> DashboardScreen(state, vm)
                SiloTab.FIREWALL -> FirewallScreen(state, vm)
                SiloTab.ISOLATION -> IsolationScreen(state, vm)
                SiloTab.MONITOR -> MonitorScreen(state, vm)
                SiloTab.HARDENING -> HardeningScreen(state, vm)
                SiloTab.SENTINEL -> SentinelScreen(state, vm)
            }
        }
    }
}
