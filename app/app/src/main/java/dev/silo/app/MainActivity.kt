package dev.silo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.i18n.resolveStrings
import dev.silo.app.ui.screens.*
import dev.silo.app.ui.theme.SiloTheme

private enum class SiloNavTab(val icon: ImageVector) {
    HOME(Icons.Rounded.Shield),
    APPS(Icons.Rounded.Apps),
    TRAFFIC(Icons.AutoMirrored.Rounded.ShowChart),
    SYSTEM(Icons.Rounded.Tune),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SiloRootHost()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiloRootHost(vm: SiloViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val strings = remember(state.languagePref) { resolveStrings(state.languagePref) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Real-time lifecycle sync: immediately reloads live Mic/Camera sensor privacy,
    // PackageManager granted permissions, Radio Privacy, and Daemon status on every resume!
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onForegroundChanged(true)
                Lifecycle.Event.ON_PAUSE -> vm.onForegroundChanged(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onForegroundChanged(false)
        }
    }

    SiloTheme(themePref = state.themePref) {
        CompositionLocalProvider(LocalSiloStrings provides strings) {
            var currentTab by remember { mutableStateOf(SiloNavTab.HOME) }
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(state.snackbarMessage) {
                val msg = state.snackbarMessage
                if (msg != null) {
                    snackbarHostState.showSnackbar(msg)
                    vm.clearSnackbar()
                }
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        title = {
                            Text(
                                when (currentTab) {
                                    SiloNavTab.HOME -> strings.titleHome
                                    SiloNavTab.APPS -> strings.titleApps
                                    SiloNavTab.TRAFFIC -> strings.titleTraffic
                                    SiloNavTab.SYSTEM -> strings.titleSystem
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        actions = {
                            IconButton(onClick = { vm.refreshAll() }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        SiloNavTab.entries.forEach { tab ->
                            val label = when (tab) {
                                SiloNavTab.HOME -> strings.tabHome
                                SiloNavTab.APPS -> strings.tabApps
                                SiloNavTab.TRAFFIC -> strings.tabTraffic
                                SiloNavTab.SYSTEM -> strings.tabSystem
                            }
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = label) },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (currentTab) {
                        SiloNavTab.HOME -> HomeScreen(state, vm)
                        SiloNavTab.APPS -> AppsScreen(state, vm)
                        SiloNavTab.TRAFFIC -> TrafficScreen(state, vm)
                        SiloNavTab.SYSTEM -> SystemScreen(state, vm)
                    }
                }
            }
        }
    }
}
