package com.relaypony.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController
import com.relaypony.session.pairing.PinnedDevice
import com.relaypony.transport.Beacon

/**
 * Advanced (plan section 12): everything a power user relies on, moved out of the main flow.
 * Pairing (paired devices, the older code check), Connections (Wi-Fi Direct, Send by address,
 * this device's addresses), the relay server, and identity (key, inbox, backups).
 */
@Composable
fun AdvancedScreen(controller: TransferController) {
    val context = LocalContext.current
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    var byAddress by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }

    // --- Backup (identity + address book) and relay server ---
    var backupAction by remember { mutableStateOf<BackupAction?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var relayText by remember { mutableStateOf(controller.relayServer) }

    fun askPassphrase(action: BackupAction, uri: Uri) {
        backupAction = action
        pendingUri = uri
        passphrase = ""
    }

    val exportIdentityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) askPassphrase(BackupAction.EXPORT_IDENTITY, uri) }
    val importIdentityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) askPassphrase(BackupAction.IMPORT_IDENTITY, uri) }
    val exportAddressesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) askPassphrase(BackupAction.EXPORT_ADDRESSES, uri) }
    val importAddressesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) askPassphrase(BackupAction.IMPORT_ADDRESSES, uri) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Pairing ----
        PairedDevicesSection(controller)
        VerifyNearbySection(controller)

        HorizontalDivider()

        // ---- Connections ----
        Text(stringResource(R.string.adv_connections), style = MaterialTheme.typography.titleMedium)
        if (controller.wifiDirect.isSupported) {
            // With files staged this device sends over Wi-Fi Direct, otherwise it receives.
            WifiDirectSection(controller, asSender = controller.pendingShare.isNotEmpty())
        }
        LinkRow(stringResource(R.string.send_by_address_title), stringResource(R.string.adv_by_address_d)) { byAddress = true }
        Text(stringResource(R.string.rec_reachable_title), style = MaterialTheme.typography.titleSmall)
        if (controller.reachableAddresses.isEmpty()) {
            Text(stringResource(R.string.home_no_addresses), style = MaterialTheme.typography.bodySmall)
        } else {
            controller.reachableAddresses.forEach { address ->
                Text(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.rec_reachable_hint), style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        // ---- Relay server ----
        Text(stringResource(R.string.set_relay_header), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.set_relay_body),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = relayText,
            onValueChange = { relayText = it },
            label = { Text(stringResource(R.string.set_relay_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                controller.relayServer = relayText.trim()
                relayText = controller.relayServer
                controller.showNotice(context.getString(R.string.set_relay_saved))
            }) { Text(stringResource(R.string.set_relay_save)) }
            OutlinedButton(onClick = {
                controller.relayServer = ""
                relayText = controller.relayServer
                controller.showNotice(context.getString(R.string.set_relay_reset_done))
            }) { Text(stringResource(R.string.set_relay_reset)) }
        }
        LinkRow(stringResource(R.string.set_relay_selfhost_t), stringResource(R.string.set_relay_selfhost_d)) { openUrl(AppLinks.SELF_HOST) }
        LinkRow(stringResource(R.string.set_relay_repo_t), stringResource(R.string.set_relay_repo_d)) { openUrl(AppLinks.RELAY_REPO) }

        HorizontalDivider()

        // ---- Identity ----
        Text(stringResource(R.string.adv_identity), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.adv_key_label), style = MaterialTheme.typography.titleSmall)
        Text(
            controller.myHandle,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("RelayPony", controller.myHandle))
            }) { Text(stringResource(R.string.pair_copy)) }
            OutlinedButton(onClick = { confirmRotate = true }) { Text(stringResource(R.string.adv_rotate_inbox)) }
        }
        Text(stringResource(R.string.set_backup_header), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.set_backup_body),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { exportIdentityLauncher.launch("relaypony-identity.age") },
                enabled = !controller.identityBusy.value,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.set_backup_export_identity)) }
            OutlinedButton(
                onClick = { importIdentityLauncher.launch(arrayOf("*/*")) },
                enabled = !controller.identityBusy.value,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.set_backup_import_identity)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { exportAddressesLauncher.launch("relaypony-addresses.age") },
                enabled = !controller.identityBusy.value,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.set_backup_export_addresses)) }
            OutlinedButton(
                onClick = { importAddressesLauncher.launch(arrayOf("*/*")) },
                enabled = !controller.identityBusy.value,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.set_backup_import_addresses)) }
        }
    }

    if (byAddress) SendByAddressDialog(controller) { byAddress = false }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.adv_rotate_inbox)) },
            text = { Text(stringResource(R.string.adv_rotate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.rotateInbox()
                    controller.showNotice(context.getString(R.string.adv_rotate_done))
                    confirmRotate = false
                }) { Text(stringResource(R.string.adv_rotate_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.pair_cancel)) } },
        )
    }

    val action = backupAction
    if (action != null) {
        val exporting = action == BackupAction.EXPORT_IDENTITY || action == BackupAction.EXPORT_ADDRESSES
        val title = when (action) {
            BackupAction.EXPORT_IDENTITY -> stringResource(R.string.set_backup_export_identity)
            BackupAction.IMPORT_IDENTITY -> stringResource(R.string.set_backup_import_identity)
            BackupAction.EXPORT_ADDRESSES -> stringResource(R.string.set_backup_export_addresses)
            BackupAction.IMPORT_ADDRESSES -> stringResource(R.string.set_backup_import_addresses)
        }
        AlertDialog(
            onDismissRequest = { backupAction = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (exporting) stringResource(R.string.set_backup_pass_export)
                        else stringResource(R.string.set_backup_pass_import),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.set_backup_pass_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        val uri = pendingUri
                        val pass = passphrase
                        if (uri != null) {
                            when (action) {
                                BackupAction.EXPORT_IDENTITY -> controller.exportIdentity(uri, pass)
                                BackupAction.IMPORT_IDENTITY -> controller.importIdentity(uri, pass)
                                BackupAction.EXPORT_ADDRESSES -> controller.exportAddresses(uri, pass)
                                BackupAction.IMPORT_ADDRESSES -> controller.importAddresses(uri, pass)
                            }
                        }
                        backupAction = null
                        passphrase = ""
                    },
                ) { Text(if (exporting) stringResource(R.string.set_backup_export_action) else stringResource(R.string.set_backup_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { backupAction = null; passphrase = "" }) { Text(stringResource(R.string.set_close)) }
            },
        )
    }
}

private enum class BackupAction { EXPORT_IDENTITY, IMPORT_IDENTITY, EXPORT_ADDRESSES, IMPORT_ADDRESSES }

/**
 * Every paired device, with Unpair behind a confirmation (PROTOCOL_v3.md section 4.7). Unpairing
 * also tells the other device, so it forgets this one too.
 */
@Composable
private fun PairedDevicesSection(controller: TransferController) {
    val revision = controller.trustRevision.intValue
    val devices = remember(revision) { controller.pairedDevices().sortedBy { it.name.lowercase() } }
    var confirm by remember { mutableStateOf<PinnedDevice?>(null) }

    Text(stringResource(R.string.set_paired_header), style = MaterialTheme.typography.titleMedium)
    if (devices.isEmpty()) {
        Text(stringResource(R.string.set_paired_empty), style = MaterialTheme.typography.bodySmall)
    }
    devices.forEach { device ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        device.recipientHandle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { confirm = device }) { Text(stringResource(R.string.set_paired_remove)) }
            }
        }
    }

    confirm?.let { device ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.set_unpair_title, device.name)) },
            text = { Text(stringResource(R.string.set_unpair_body, device.name)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.unpair(device.recipientHandle)
                    confirm = null
                }) { Text(stringResource(R.string.set_unpair_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.pair_cancel)) } },
        )
    }
}

/**
 * The older 6-digit check for a device discovered on this network (PROTOCOL_v3 compatibility with
 * RelayPony 3). Both handles come from discovery, so the code proves less than a QR or word code;
 * it stays here for devices that can't do either.
 */
@Composable
private fun VerifyNearbySection(controller: TransferController) {
    val revision = controller.trustRevision.intValue
    val unpaired = controller.peers.filter { revision >= 0 && !controller.isPinned(it.recipientHandle) }
    Text(stringResource(R.string.adv_verify_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.adv_verify_body), style = MaterialTheme.typography.bodySmall)
    if (unpaired.isEmpty()) {
        Text(stringResource(R.string.adv_verify_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    unpaired.forEach { peer ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(peer.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { controller.stageDiscovered(peer) }) { Text(stringResource(R.string.verify_action)) }
            }
        }
    }

    controller.pendingVerify.value?.let { pv ->
        AlertDialog(
            onDismissRequest = { controller.dismissVerify() },
            title = { Text(stringResource(R.string.verify_title, pv.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.verify_code_label), style = MaterialTheme.typography.labelMedium)
                    Text(
                        pv.sas,
                        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.verify_hint, pv.name), style = MaterialTheme.typography.bodySmall)
                    Text(pv.handle, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { controller.confirmVerify() }) { Text(stringResource(R.string.verify_pair)) }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissVerify() }) { Text(stringResource(R.string.pair_cancel)) }
            },
        )
    }
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
