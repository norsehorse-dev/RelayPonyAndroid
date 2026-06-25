package com.relaypony.android.transfer

import com.relaypony.android.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.relaypony.session.inbox.ReceivedFile

@Composable
fun TransferScreen(controller: TransferController, modifier: Modifier = Modifier) {
    var showQr by remember { mutableStateOf(false) }
    val qrBitmap = remember { QrImage.generate(controller.myQrText()) }
    val selected = remember { mutableStateListOf<String>() }

    // Keep the Wi-Fi Direct broadcast receiver live while this screen is composed.
    DisposableEffect(Unit) {
        controller.wifiDirect.register()
        onDispose { controller.wifiDirect.unregister() }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { controller.pinFromScan(it) }
    }
    val pickFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) controller.setPendingShareFromUris(uris)
    }
    fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt("Scan the other device's RelayPony QR")
        )
    }

    var pendingSave by remember { mutableStateOf<ReceivedFile?>(null) }
    val savePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingSave
        pendingSave = null
        if (granted && file != null) controller.saveToDownloads(file)
        else if (!granted) controller.status.value = "Storage permission denied"
    }
    fun save(file: ReceivedFile) {
        if (controller.needsStoragePermission()) {
            pendingSave = file
            savePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            controller.saveToDownloads(file)
        }
    }

    val autoSavePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        controller.setAutoSave(granted)
        if (!granted) controller.status.value = "Auto-save needs storage permission"
    }
    fun toggleAutoSave(enable: Boolean) {
        if (enable && controller.needsStoragePermission()) {
            autoSavePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            controller.setAutoSave(enable)
        }
    }

    // Wi-Fi Direct discovery needs location / nearby-wifi permission first.
    val wifiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) controller.wifiDirect.discover()
        else controller.wifiDirect.lastError.value = UiText(R.string.wd_perm_msg)
    }
    fun discoverWifiDirect() {
        wifiPermLauncher.launch(controller.wifiDirectPermissions())
    }

    val sharing = controller.pendingShare.isNotEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("RelayPony", style = MaterialTheme.typography.headlineMedium)
            Text("Device: ${controller.deviceName}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Key: ${controller.myHandle}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { controller.startReceiving() }) { Text("Start receiving") }
                Button(onClick = { controller.startDiscovery() }) { Text("Discover") }
                OutlinedButton(onClick = { showQr = !showQr }) { Text(if (showQr) "Hide QR" else "My QR") }
            }
        }

        item {
            Button(onClick = { pickFilesLauncher.launch(arrayOf("*/*")) }) {
                Text("Pick files to send")
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = controller.autoSave.value,
                    onCheckedChange = { toggleAutoSave(it) },
                )
                Text(
                    "Auto-save received files to Downloads",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (showQr) {
            item {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR",
                    modifier = Modifier.size(220.dp),
                )
                Text("Have the other device scan this to pair.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (sharing) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Ready to send ${controller.pendingShare.size} file(s):",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        controller.pendingShare.forEach { file ->
                            Text("\u2022 ${file.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Text("Status: ${controller.status.value}", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }

        item { Text("Received", style = MaterialTheme.typography.titleMedium) }
        if (controller.inbox.isEmpty()) {
            item { Text("Nothing received yet.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(controller.inbox) { file ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${file.size} bytes \u00b7 from ${file.fromDevice}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { controller.openFile(file) }) { Text("Open") }
                            if (file.savedToDownloads) {
                                Text(
                                    "Saved",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                            } else {
                                TextButton(onClick = { save(file) }) { Text("Save to Downloads") }
                            }
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                if (selected.isNotEmpty()) {
                    Button(onClick = {
                        val targets = controller.peers.filter { controller.peerKey(it) in selected }
                        controller.sendToGroup(targets)
                    }) {
                        Text(
                            if (sharing) "Send ${controller.pendingShare.size} to ${selected.size}"
                            else "Send test to ${selected.size}"
                        )
                    }
                }
            }
        }

        val revision = controller.trustRevision.intValue
        items(controller.peers) { peer ->
            val key = controller.peerKey(peer)
            val pinned = revision.let { controller.isPinned(peer.recipientHandle) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        Text("${peer.host}:${peer.port}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (pinned) "Paired" else "Not paired",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        controller.sendStatus[key]?.let { s ->
                            Text(s, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!pinned) {
                            Button(
                                onClick = { launchScan() },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Pair (scan QR)") }
                        }
                    }
                }
            }
        }

        // --- Wi-Fi Direct (Phase 7a: link only, no transfer yet) ---
        item {
            HorizontalDivider()
            Text("Wi-Fi Direct (no shared Wi-Fi needed)", style = MaterialTheme.typography.titleMedium)
            Text(
                "State: ${controller.wifiDirect.connectionState.value.resolve()}",
                style = MaterialTheme.typography.bodySmall,
            )
            controller.wifiDirect.groupOwnerAddress.value?.let {
                Text("Group owner: $it", style = MaterialTheme.typography.bodySmall)
            }
            controller.wifiDirect.lastError.value?.let {
                Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Transfer: ${controller.wifiTransferStatus.value.resolve()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { discoverWifiDirect() }) { Text("Discover Wi-Fi Direct") }
                OutlinedButton(onClick = { controller.wifiDirect.disconnect() }) { Text("Disconnect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { controller.armWifiDirect(asSender = false) }) { Text("Arm receive") }
                Button(onClick = { controller.armWifiDirect(asSender = true) }) {
                    Text(if (sharing) "Arm send (${controller.pendingShare.size})" else "Arm send (test)")
                }
            }
        }

        items(controller.wifiDirect.p2pPeers) { device ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            device.deviceName.ifEmpty { "(unnamed device)" },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(device.deviceAddress, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { controller.wifiDirect.connect(device) }) { Text("Connect") }
                }
            }
        }
    }
}
