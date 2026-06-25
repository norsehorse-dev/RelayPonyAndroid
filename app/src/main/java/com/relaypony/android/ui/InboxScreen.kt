package com.relaypony.android.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController
import com.relaypony.session.inbox.ReceivedFile
import java.util.Locale

@Composable
fun InboxScreen(controller: TransferController) {
    var pendingSave by remember { mutableStateOf<ReceivedFile?>(null) }
    var pendingDelete by remember { mutableStateOf<ReceivedFile?>(null) }

    val storagePermMsg = stringResource(R.string.inbox_perm_msg)
    val savePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingSave
        pendingSave = null
        if (granted && file != null) controller.saveToDownloads(file)
        else if (!granted) controller.status.value = storagePermMsg
    }
    fun save(file: ReceivedFile) {
        if (controller.needsStoragePermission()) {
            pendingSave = file
            savePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            controller.saveToDownloads(file)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (controller.inbox.isEmpty()) {
            Text(stringResource(R.string.nav_inbox), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.inbox_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                stringResource(R.string.inbox_header, controller.inbox.size),
                style = MaterialTheme.typography.titleMedium,
            )
            controller.inbox.forEach { file ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(extOf(file.name))
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.inbox_size_from, formatSize(file.size), file.fromDevice),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    relativeTime(file.receivedAtEpochMs),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = { controller.openFile(file) }) { Text(stringResource(R.string.inbox_open)) }
                            if (file.savedToDownloads) {
                                Text(
                                    stringResource(R.string.inbox_saved),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                            } else {
                                TextButton(onClick = { save(file) }) { Text(stringResource(R.string.inbox_save)) }
                            }
                            TextButton(onClick = { pendingDelete = file }) {
                                Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.inbox_delete_title)) },
            text = {
                Text(
                    if (file.savedToDownloads) {
                        stringResource(R.string.inbox_delete_msg_saved, file.name)
                    } else {
                        stringResource(R.string.inbox_delete_msg_plain, file.name)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteReceived(file)
                    pendingDelete = null
                }) { Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }
}

@Composable
private fun TypeBadge(ext: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ext,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun extOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot in 1 until name.length - 1) name.substring(dot + 1).take(4).uppercase(Locale.US) else "FILE"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

@Composable
private fun relativeTime(epochMs: Long): String =
    // Resolve through the app-locale-wrapped LocalContext (provided by MainActivity) so the relative
    // time follows the in-app language instead of the process/system default locale.
    DateUtils.getRelativeDateTimeString(
        LocalContext.current, epochMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0,
    ).toString()
