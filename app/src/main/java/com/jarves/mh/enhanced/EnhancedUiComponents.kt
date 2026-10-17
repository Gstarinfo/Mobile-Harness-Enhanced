package com.jarves.mh.enhanced

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun EnhancedExportProgressDialog(
    exportLoading: Boolean,
    fileName: String?,
    message: String?,
    progress: Float,
    bytes: Pair<Long, Long>?,
) {
    if (!exportLoading) return

    Dialog(onDismissRequest = { /* Modal during active export */ }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                    )
                    Text(
                        text = fileName ?: "Exporting…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (bytes != null && bytes.second > 0L) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        strokeCap = StrokeCap.Round,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${EnhancedFileOperations.formatFileSize(bytes.first)} / ${EnhancedFileOperations.formatFileSize(bytes.second)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

@Composable
fun EnhancedFileMenuItems(
    filePath: String,
    onOpenWith: () -> Unit,
    onExportFile: () -> Unit,
    onInstallApk: (() -> Unit)? = null,
) {
    DropdownMenuItem(
        text = { Text("Open with…") },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
        onClick = onOpenWith,
    )
    DropdownMenuItem(
        text = { Text("Export file…") },
        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
        onClick = onExportFile,
    )
    if (filePath.endsWith(".apk", ignoreCase = true) && onInstallApk != null) {
        DropdownMenuItem(
            text = { Text("Install APK") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = onInstallApk,
        )
    }
}
