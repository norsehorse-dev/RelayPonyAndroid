package com.relaypony.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.BuildConfig
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController

private val LANGUAGES = listOf(
    "en" to "English",
    "es" to "Espa\u00f1ol",
    "de" to "Deutsch",
    "fr" to "Fran\u00e7ais",
    "ja" to "\u65e5\u672c\u8a9e",
    "pt-BR" to "Portugu\u00eas (BR)",
    "hi" to "\u0939\u093f\u0928\u094d\u0926\u0940",
)

private fun languageName(code: String): String =
    LANGUAGES.firstOrNull { it.first == code }?.second ?: "English"

@Composable
fun SettingsScreen(controller: TransferController) {
    val context = LocalContext.current
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    var showLangPicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    val autoSavePermMsg = stringResource(R.string.set_autosave_perm)

    val autoSavePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        controller.setAutoSave(granted)
        if (!granted) controller.status.value = autoSavePermMsg
    }
    fun toggleAutoSave(enable: Boolean) {
        if (enable && controller.needsStoragePermission()) {
            autoSavePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            controller.setAutoSave(enable)
        }
    }

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

    var showWanDev by remember { mutableStateOf(false) }
    if (showWanDev) {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = { showWanDev = false }) { Text("\u2039 Back") }
            WanDirectDevScreen(controller.myScalar, controller.myHandle)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier.fillMaxWidth().clickable { showLangPicker = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.set_app_language), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageName(controller.languageCode.value),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { showThemePicker = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (controller.themeMode.value) {
                        TransferController.ThemeMode.SYSTEM -> stringResource(R.string.set_theme_system)
                        TransferController.ThemeMode.LIGHT -> stringResource(R.string.set_theme_light)
                        TransferController.ThemeMode.DARK -> stringResource(R.string.set_theme_dark)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.set_transfers), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(stringResource(R.string.set_autosave_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.set_autosave_detail),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = controller.autoSave.value, onCheckedChange = { toggleAutoSave(it) })
        }

        HorizontalDivider()

        Text(stringResource(R.string.set_this_device), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.set_name, controller.deviceName), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.set_key, controller.myHandle),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider()

        Text(stringResource(R.string.set_help_header), style = MaterialTheme.typography.titleMedium)
        LinkRow(stringResource(R.string.set_help_faq_t), stringResource(R.string.set_help_faq_d)) { openUrl(AppLinks.SUPPORT) }
        LinkRow(stringResource(R.string.set_help_rate_t), stringResource(R.string.set_help_rate_d)) { openUrl(AppLinks.PLAY) }
        LinkRow(stringResource(R.string.set_help_feedback_t), stringResource(R.string.set_help_feedback_d)) {
            openUrl("mailto:${AppLinks.FEEDBACK_EMAIL}?subject=" + Uri.encode("RelayPony Android Feedback (${BuildConfig.VERSION_NAME})"))
        }
        LinkRow(stringResource(R.string.set_help_privacy_t), stringResource(R.string.set_help_privacy_d)) { openUrl(AppLinks.PRIVACY) }
        LinkRow(stringResource(R.string.set_help_security_t), stringResource(R.string.set_help_security_d)) { openUrl(AppLinks.SECURITY) }

        HorizontalDivider()

        Text(stringResource(R.string.set_more_header), style = MaterialTheme.typography.titleMedium)
        LinkRow(stringResource(R.string.set_more_pgpony), stringResource(R.string.set_more_pgpony_d)) { openUrl(AppLinks.PGPONY) }
        LinkRow(stringResource(R.string.set_more_agepony), stringResource(R.string.set_more_agepony_d)) { openUrl(AppLinks.AGEPONY) }
        LinkRow(stringResource(R.string.set_more_quorumpony), stringResource(R.string.set_more_quorumpony_d)) { openUrl(AppLinks.QUORUMPONY) }
        LinkRow(stringResource(R.string.set_more_carrierpony), stringResource(R.string.set_more_carrierpony_d)) { openUrl(AppLinks.CARRIERPONY) }
        LinkRow(stringResource(R.string.set_more_burnpony), stringResource(R.string.set_more_burnpony_d)) { openUrl(AppLinks.BURNPONY) }
        LinkRow(stringResource(R.string.set_more_vaultpony), stringResource(R.string.set_more_vaultpony_d)) { openUrl(AppLinks.VAULTPONY) }
        LinkRow(stringResource(R.string.set_more_passpony), stringResource(R.string.set_more_passpony_d)) { openUrl(AppLinks.PASSPONY) }
        LinkRow(stringResource(R.string.set_more_scrubpony), stringResource(R.string.set_more_scrubpony_d)) { openUrl(AppLinks.SCRUBPONY) }
        LinkRow(stringResource(R.string.set_more_family_t), stringResource(R.string.set_more_family_d)) { openUrl(AppLinks.PONY_FAMILY) }
        LinkRow(stringResource(R.string.set_more_appsrc_t), stringResource(R.string.set_more_appsrc_d)) { openUrl(AppLinks.REPO) }
        LinkRow(stringResource(R.string.set_more_core_t), stringResource(R.string.set_more_core_d)) { openUrl(AppLinks.CORE_REPO) }

        HorizontalDivider()

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

        HorizontalDivider()

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
                controller.status.value = context.getString(R.string.set_relay_saved)
            }) { Text(stringResource(R.string.set_relay_save)) }
            OutlinedButton(onClick = {
                controller.relayServer = ""
                relayText = controller.relayServer
                controller.status.value = context.getString(R.string.set_relay_reset_done)
            }) { Text(stringResource(R.string.set_relay_reset)) }
        }
        LinkRow(stringResource(R.string.set_relay_selfhost_t), stringResource(R.string.set_relay_selfhost_d)) { openUrl(AppLinks.SELF_HOST) }
        LinkRow(stringResource(R.string.set_relay_repo_t), stringResource(R.string.set_relay_repo_d)) { openUrl(AppLinks.RELAY_REPO) }

        HorizontalDivider()

        Text("Developer", style = MaterialTheme.typography.titleMedium)
        LinkRow("WAN Direct (dev)", "Serverless path + 1 MB test stream to a paired peer") { showWanDev = true }

        HorizontalDivider()

        Text(stringResource(R.string.set_about), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.set_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.set_about_compat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.set_about_encryption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.set_about_transport), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinkRow(stringResource(R.string.set_about_licenses_t), stringResource(R.string.set_about_licenses_d)) { openUrl(AppLinks.OPEN_SOURCE) }
        Text(
            stringResource(R.string.set_about_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { controller.replayOnboarding() }) { Text(stringResource(R.string.set_replay)) }
    }

    if (showLangPicker) {
        AlertDialog(
            onDismissRequest = { showLangPicker = false },
            title = { Text(stringResource(R.string.set_app_language)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    LANGUAGES.forEach { (code, label) ->
                        val live = code in Locales.LIVE
                        val selected = controller.languageCode.value == code
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (live) Modifier.clickable {
                                        controller.setLanguage(code)
                                        showLangPicker = false
                                    } else Modifier
                                )
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                color = if (live) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when {
                                selected -> Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.lang_selected), tint = MaterialTheme.colorScheme.primary)
                                !live -> Text(stringResource(R.string.lang_soon), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangPicker = false }) { Text(stringResource(R.string.set_close)) }
            },
        )
    }

    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text(stringResource(R.string.set_theme)) },
            text = {
                Column {
                    val modes = listOf(
                        TransferController.ThemeMode.SYSTEM to stringResource(R.string.set_theme_system),
                        TransferController.ThemeMode.LIGHT to stringResource(R.string.set_theme_light),
                        TransferController.ThemeMode.DARK to stringResource(R.string.set_theme_dark),
                    )
                    modes.forEach { (mode, label) ->
                        val selected = controller.themeMode.value == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.setThemeMode(mode)
                                    showThemePicker = false
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label)
                            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.lang_selected), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) { Text(stringResource(R.string.set_close)) }
            },
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

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
