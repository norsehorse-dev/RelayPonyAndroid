package com.relaypony.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController

@Composable
fun SendScreen(controller: TransferController) {
    val selected = remember { mutableStateListOf<String>() }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { controller.pinFromScan(it) }
    }
    val scanPrompt = stringResource(R.string.send_scan_prompt)
    fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt(scanPrompt)
        )
    }
    val pickFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) controller.setPendingShareFromUris(uris)
    }

    val sharing = controller.pendingShare.isNotEmpty()
    val selectedPaired = controller.peers.filter {
        controller.peerKey(it) in selected && controller.isPinned(it.recipientHandle)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.send_files_title), style = MaterialTheme.typography.titleMedium)
        if (sharing) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.send_files_ready, controller.pendingShare.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(onClick = { controller.clearPendingShare() }) { Text(stringResource(R.string.send_clear)) }
                    }
                    controller.pendingShare.forEach { file ->
                        Text("\u2022 ${file.name}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(
                onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.send_add_more)) }
        } else {
            Button(
                onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.send_pick_files)) }
            Text(
                stringResource(R.string.send_or_share),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.send_to_device_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { controller.startDiscovery() }) { Text(stringResource(R.string.send_refresh)) }
        }
        if (controller.peers.isEmpty()) {
            Text(
                stringResource(R.string.send_looking),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val revision = controller.trustRevision.intValue
        controller.peers.forEach { peer ->
            val key = controller.peerKey(peer)
            val pinned = revision.let { controller.isPinned(peer.recipientHandle) }
            val sendState = controller.sendStatus[key]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pinned) {
                            Checkbox(
                                checked = key in selected,
                                onCheckedChange = { checked ->
                                    if (checked) { if (key !in selected) selected.add(key) }
                                    else selected.remove(key)
                                },
                            )
                        }
                        Column(modifier = Modifier.padding(start = if (pinned) 4.dp else 0.dp)) {
                            Text(peer.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (pinned) stringResource(R.string.send_paired) else stringResource(R.string.send_not_paired),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (!pinned) {
                        Button(
                            onClick = { launchScan() },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(R.string.send_pair)) }
                    }
                    if (sendState != null) {
                        Text(
                            sendState,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (controller.sendInProgress[key] == true) {
                            val progress = controller.sendProgress[key] ?: 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (selectedPaired.isNotEmpty()) {
            if (sharing) {
                Button(
                    onClick = { controller.sendToGroup(selectedPaired) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.send_send_files, controller.pendingShare.size, selectedPaired.size))
                }
            } else {
                Text(
                    stringResource(R.string.send_pick_first),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { controller.sendToGroup(selectedPaired) }) {
                    Text(stringResource(R.string.send_test_instead))
                }
            }
        }

        HorizontalDivider()
        WifiDirectSection(controller, asSender = true)
    }
}
