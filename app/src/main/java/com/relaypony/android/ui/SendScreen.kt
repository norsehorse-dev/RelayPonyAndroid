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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.PairingController
import com.relaypony.android.transfer.TransferController
import com.relaypony.transport.Beacon

@Composable
fun SendScreen(controller: TransferController) {
    val selected = remember { mutableStateListOf<String>() }
    var byAddress by remember { mutableStateOf(false) }
    // TV: no camera means no QR scanning; pairing goes through the word code tab instead.
    val canScan = rememberHasCamera()
    // TV: Google TV has no document picker, so offering "pick files" would dead-end in a
    // "no app can perform this action" system message.
    val canPickFiles = rememberCanPickDocuments()

    // A4: when a Direct Share target opened us, auto-check that device once it is discovered and
    // still pinned. Best-effort: if the launcher didn't pass the id, or the peer isn't found, the
    // files are staged anyway and the user picks normally.
    LaunchedEffect(controller.peers.size, controller.preselectHandle.value) {
        val wanted = controller.preselectHandle.value ?: return@LaunchedEffect
        val match = controller.peers.firstOrNull {
            it.recipientHandle == wanted && controller.isPinned(it.recipientHandle)
        } ?: return@LaunchedEffect
        val key = controller.peerKey(match)
        if (key !in selected) selected.add(key)
        controller.preselectHandle.value = null
    }

    // Pairing happens in the pair sheet. Opened from here with files staged, the transfer starts
    // as soon as the new device is pinned (pair-and-send). Scan first when there's a camera.
    val pairTab = if (canScan) PairingController.Tab.SCAN else PairingController.Tab.WORD
    fun openPairSheet() = controller.pair.open(pairTab, sendAfter = controller.pendingShare.isNotEmpty())
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
            if (canPickFiles) {
                OutlinedButton(
                    onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.send_add_more)) }
            }
        } else if (canPickFiles) {
            Button(
                onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.send_pick_files)) }
            Text(
                stringResource(R.string.send_or_share),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                stringResource(R.string.send_no_picker),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { byAddress = true }) {
                    Text(stringResource(R.string.send_by_address))
                }
                OutlinedButton(onClick = { controller.startDiscovery() }) {
                    Text(stringResource(R.string.send_refresh))
                }
            }
        }
        OutlinedButton(onClick = { openPairSheet() }, modifier = Modifier.fillMaxWidth()) {
            Icon(QrCodeIcon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.send_pair_new))
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
                        Button(onClick = { openPairSheet() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.send_pair_peer))
                        }
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
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.send_wan_header), style = MaterialTheme.typography.titleMedium)
        val nearbyHandles = controller.peers.map { it.recipientHandle }.toSet()
        val remoteDevices = controller.pairedDevices().filter { it.recipientHandle !in nearbyHandles }
        if (remoteDevices.isEmpty()) {
            Text(
                stringResource(R.string.send_wan_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            remoteDevices.forEach { device ->
                val wanStatus = controller.wanSendStatus[device.recipientHandle]
                val wanBusy = device.recipientHandle in controller.wanSending.value
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.send_paired),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Button(onClick = { controller.sendWAN(device) }, enabled = sharing && !wanBusy) {
                                Text(stringResource(R.string.send_wan_send))
                            }
                        }
                        if (wanStatus != null) {
                            Text(wanStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (wanBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        if (controller.wifiDirect.isSupported) {
            HorizontalDivider()
            WifiDirectSection(controller, asSender = true)
        }
    }

    if (byAddress) SendByAddressDialog(controller) { byAddress = false }
}

/**
 * Send to an already-paired device at an address typed by hand.
 *
 * The way out of every network discovery can't cross — a hotspot, guest Wi-Fi that isolates
 * clients, a VPN. Pairing is what supplies the key, the one thing an address can't, so this only
 * offers devices already in the trust store: the security model is unchanged, we've just found the
 * socket a different way.
 */
@Composable
private fun SendByAddressDialog(controller: TransferController, onDismiss: () -> Unit) {
    val paired = controller.pairedDevices()
    var selectedHandle by remember { mutableStateOf(paired.firstOrNull()?.recipientHandle ?: "") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Beacon.DEFAULT_TRANSFER_PORT.toString()) }
    val portNumber = port.trim().toIntOrNull()
    val ready = selectedHandle.isNotEmpty() && host.isNotBlank() && portNumber != null && portNumber in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_by_address_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.send_by_address_help), style = MaterialTheme.typography.bodySmall)
                if (paired.isEmpty()) {
                    Text(stringResource(R.string.send_no_paired), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.send_by_address_device), style = MaterialTheme.typography.labelMedium)
                    paired.forEach { device ->
                        val chosen = device.recipientHandle == selectedHandle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = chosen, onCheckedChange = { selectedHandle = device.recipientHandle })
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.send_by_address_host)) },
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.send_by_address_port)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val device = paired.firstOrNull { it.recipientHandle == selectedHandle }
                    if (device != null && portNumber != null) {
                        controller.sendToAddress(host, portNumber, device.recipientHandle, device.name)
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.send_by_address_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.inbox_cancel)) } },
    )
}
