package dev.silo.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.ipc.AuditFinding
import dev.silo.app.ipc.AuditSeverity
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.theme.SiloAmber
import dev.silo.app.ui.theme.SiloCrimson
import dev.silo.app.ui.theme.SiloCyan
import dev.silo.app.ui.theme.SiloEmerald

@Composable
fun SentinelScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportConfigToUri(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importConfigFromUri(uri)
    }

    val report = state.sentinelReport

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Audit Summary Header
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Zero-Leak Stealth & Integrity Sentinel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Read-only audit — zero conflict with SUSFS, NoMount, HMA-OSS, or Baseband-guard",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.weight(1f), onClick = { vm.runSentinelAudit() }) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run Live Audit")
                        }
                    }
                    if (report != null) {
                        Text(
                            "Posture Score: ${report.score}/100  •  SELinux Enforcing: ${report.selinuxEnforcing}  •  User CAs: ${report.userCaCount}  •  App Listeners: ${report.appListeningPorts}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (report.score >= 85) SiloEmerald else SiloAmber,
                        )
                    }
                }
            }
        }

        // Findings List
        if (report != null) {
            items(report.findings, key = { it.id }) { finding ->
                FindingCard(finding)
            }
        }

        // Stealth Disguise & Offline Config Backup/Restore
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Stealth & Configuration Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    QuickSwitchRow(
                        title = "App Disguise Mode (NetDiag)",
                        subtitle = "Switch launcher icon & label via Android activity-alias (pair with KSU allowlist + HMA-OSS)",
                        checked = state.disguiseEnabled,
                        onCheckedChange = { vm.setDisguiseEnabled(it) },
                    )

                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { exportLauncher.launch("silo-backup.json") },
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export JSON")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import JSON")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingCard(finding: AuditFinding) {
    val (color, badge) = when (finding.severity) {
        AuditSeverity.PASS -> SiloEmerald to "PASS"
        AuditSeverity.INFO -> SiloCyan to "INFO"
        AuditSeverity.WARN -> SiloAmber to "WARN"
        AuditSeverity.CRITICAL -> SiloCrimson to "CRIT"
    }
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(finding.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            finding.recommendation?.let { rec ->
                Text("Action: $rec", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}
