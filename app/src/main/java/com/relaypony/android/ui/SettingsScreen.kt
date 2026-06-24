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

        Text(stringResource(R.string.set_more_apps), style = MaterialTheme.typography.titleMedium)
        LinkRow("PGPony", stringResource(R.string.set_pgpony_desc)) { openUrl(AppLinks.PGPONY) }
        LinkRow("AgePony", stringResource(R.string.set_agepony_desc)) { openUrl(AppLinks.AGEPONY) }

        HorizontalDivider()

        Text(stringResource(R.string.set_open_source), style = MaterialTheme.typography.titleMedium)
        LinkRow(stringResource(R.string.set_source_title), stringResource(R.string.set_source_desc)) { openUrl(AppLinks.REPO) }

        HorizontalDivider()

        Text(stringResource(R.string.set_about), style = MaterialTheme.typography.titleMedium)
        Text(
            "RelayPony ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
        )
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
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
