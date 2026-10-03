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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.relaypony.android.transfer.ClipText
import com.relaypony.android.transfer.FolderExtractor
import com.relaypony.android.transfer.PairingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.relaypony.android.transfer.TransferController
import com.relaypony.session.inbox.ReceivedFile
import java.util.Locale

@Composable
fun InboxScreen(controller: TransferController) {
    var pendingSave by remember { mutableStateOf<ReceivedFile?>(null) }
    var pendingExtract by remember { mutableStateOf<ReceivedFile?>(null) }
    var pendingDelete by remember { mutableStateOf<ReceivedFile?>(null) }

    val storagePermMsg = stringResource(R.string.inbox_perm_msg)
    val savePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingSave
        val zip = pendingExtract
        pendingSave = null
        pendingExtract = null
        if (granted && file != null) controller.saveToDownloads(file)
        else if (granted && zip != null) controller.extractFolder(zip)
        else if (!granted) controller.showNotice(storagePermMsg)
    }
    fun save(file: ReceivedFile) {
        if (controller.needsStoragePermission()) {
            pendingSave = file
            savePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            controller.saveToDownloads(file)
        }
    }
    fun extract(file: ReceivedFile) {
        if (controller.needsStoragePermission()) {
            pendingExtract = file
            savePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            controller.extractFolder(file)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Refused senders (PROTOCOL_v3 section 9.3): pairing back lets them send.
        controller.requests.toList().forEach { request ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.req_title, request.name), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.req_body), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { controller.pair.open(PairingController.Tab.QR) }) {
                            Text(stringResource(R.string.send_pair_peer))
                        }
                        TextButton(onClick = { controller.dismissRequest(request) }) {
                            Text(stringResource(R.string.req_dismiss))
                        }
                    }
                }
            }
        }
        if (controller.receiveInProgress.value || controller.wanReceiving.value) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rec_receiving), style = MaterialTheme.typography.bodyMedium)
                    if (controller.receiveInProgress.value) {
                        LinearProgressIndicator(progress = { controller.receiveProgress.value }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (controller.inbox.isEmpty()) {
            Text(stringResource(R.string.nav_received), style = MaterialTheme.typography.titleMedium)
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
                        ContentActions(controller, file) { extract(file) }
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

/**
 * Extra actions by content: Extract for a zip (PROTOCOL_v3 section 12), and Copy, plus Open link
 * for a single URL, for a small text drop (plan section 5, the same rule iOS uses).
 */
@Composable
private fun ContentActions(controller: TransferController, file: ReceivedFile, onExtract: () -> Unit) {
    val context = LocalContext.current
    val clip by produceState<ClipText.Sniff?>(null, file.id) {
        if (ClipText.isCandidate(file.name, file.size)) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(file.localPath)
                    if (f.length() <= ClipText.MAX_SNIFF_BYTES) ClipText.sniff(f.readBytes()) else null
                }.getOrNull()
            }
        }
    }
    val copied = stringResource(R.string.inbox_copied)
    val linkFailed = stringResource(R.string.inbox_link_failed)
    val isZip = FolderExtractor.isArchive(file.name)
    val current = clip
    if (!isZip && current == null) return

    when (current) {
        is ClipText.Sniff.Text -> Text(
            current.text.trim(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        is ClipText.Sniff.Link -> Text(
            current.url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        null -> {}
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isZip) {
            val busy = controller.extractingId.value
            TextButton(onClick = onExtract, enabled = busy == null) {
                Text(stringResource(if (busy == file.id) R.string.inbox_extracting else R.string.inbox_extract))
            }
        }
        if (current != null) {
            TextButton(onClick = {
                val text = when (current) {
                    is ClipText.Sniff.Text -> current.text
                    is ClipText.Sniff.Link -> current.url
                }
                copyToClipboard(context, text)
                controller.showNotice(copied)
            }) { Text(stringResource(R.string.inbox_copy)) }
        }
        if (current is ClipText.Sniff.Link) {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(current.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { controller.showNotice(linkFailed) }
            }) { Text(stringResource(R.string.inbox_open_link)) }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("RelayPony", text))
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

internal fun formatSize(bytes: Long): String {
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
