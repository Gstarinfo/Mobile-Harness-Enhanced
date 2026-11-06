package com.jarves.mh.daemon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R

@Composable
fun AntiGemFallbackDialog(
    installed: Boolean,
    onStartService: () -> Unit,
    onSwitchToStandalone: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.antigem_fallback_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (installed) {
                        stringResource(R.string.antigem_fallback_stopped_body)
                    } else {
                        stringResource(R.string.antigem_fallback_missing_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (installed) {
                Button(
                    onClick = {
                        onStartService()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.antigem_action_start))
                }
            } else {
                Button(
                    onClick = {
                        onSwitchToStandalone()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.antigem_action_switch_standalone))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed) {
                    OutlinedButton(
                        onClick = {
                            onSwitchToStandalone()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.antigem_action_switch_standalone))
                    }
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.antigem_action_retry))
                }
            }
        },
    )
}

@Composable
fun RuntimeModeSelectorCard(
    currentMode: RuntimeMode,
    daemonState: DaemonDiscoveryState,
    onSelectMode: (RuntimeMode) -> Unit,
    onRefreshDaemon: () -> Unit,
    onStartService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Daemon Health Status Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    val statusDotColor = when (daemonState.status) {
                        DaemonStatus.AVAILABLE -> Color(0xFF10B981) // Green
                        DaemonStatus.UNAVAILABLE -> Color(0xFFF59E0B) // Amber
                        DaemonStatus.NOT_INSTALLED -> Color(0xFF6B7280) // Gray
                        DaemonStatus.CHECKING -> MaterialTheme.colorScheme.primary
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.antigem_daemon_status),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when (daemonState.status) {
                                DaemonStatus.AVAILABLE -> stringResource(R.string.antigem_daemon_available, daemonState.activePort ?: 1234)
                                DaemonStatus.UNAVAILABLE -> stringResource(R.string.antigem_daemon_unavailable)
                                DaemonStatus.NOT_INSTALLED -> stringResource(R.string.antigem_not_installed)
                                DaemonStatus.CHECKING -> stringResource(R.string.antigem_daemon_checking)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (daemonState.status == DaemonStatus.UNAVAILABLE && daemonState.installedPackage != null) {
                        TextButton(
                            onClick = onStartService,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(stringResource(R.string.antigem_action_start), fontSize = 11.sp)
                        }
                    }
                    IconButton(
                        onClick = onRefreshDaemon,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.antigem_action_retry),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Mode Options Cards
        RuntimeMode.entries.forEach { mode ->
            val selected = currentMode == mode
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectMode(mode) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelectMode(mode) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(mode.titleRes),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (mode == RuntimeMode.SHARED_DAEMON && daemonState.isAvailable) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Ready",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier
                                        .background(Color(0xFF10B981).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(mode.descriptionRes),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
