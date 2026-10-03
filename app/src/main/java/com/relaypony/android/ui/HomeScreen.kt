package com.relaypony.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController

/**
 * Home (plan section 4.2): what this device can receive right now, then big buttons to pick what
 * to send. Picking opens the Send-to sheet. On a TV, which has no picker, Home is the pairing QR.
 */
@Composable
fun HomeScreen(controller: TransferController) {
    val isTv = rememberIsTelevision()
    val canPickFiles = rememberCanPickDocuments()
    var showText by rememberSaveable { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.setPendingShareFromUris(uris)
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) controller.setPendingShareFromUris(uris)
    }

    if (isTv) {
        TvHome(controller)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReadyStatus(controller)
        ActiveSendCard(controller)
        StagedCard(controller)

        if (canPickFiles) {
            BigButton(FileIcon, stringResource(R.string.home_files)) { pickFiles.launch(arrayOf("*/*")) }
            BigButton(PhotosIcon, stringResource(R.string.home_photos)) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }
        BigButton(TextIcon, stringResource(R.string.home_text)) { showText = true }
        Text(
            stringResource(if (canPickFiles) R.string.home_hint else R.string.send_no_picker),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showText) TextDropDialog(controller) { showText = false }
}

/** A TV is mostly a receiver: status on one side, the pairing QR big on the other. */
@Composable
private fun TvHome(controller: TransferController) {
    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReadyStatus(controller)
            Text(stringResource(R.string.home_tv_body), style = MaterialTheme.typography.bodyLarge)
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val qrSize = minOf(maxWidth - 32.dp, maxHeight - 200.dp).coerceIn(160.dp, 400.dp)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PairQrPanel(controller, maxQr = qrSize)
            }
        }
    }
}

/** One of Home's big content buttons: at least 64 dp tall, full width. */
@Composable
private fun BigButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(CardDefaults.elevatedShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * "Ready to receive", or why not with a fix (plan sections 9.1 and 10). Tapping it shows this
 * device's name and the addresses another device can reach it at.
 */
@Composable
private fun ReadyStatus(controller: TransferController) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(false) }
    val lan = controller.isReceiving.value && controller.reachableAddresses.isNotEmpty()
    val internet = controller.wanReceiveActive.value
    val receiving = controller.receiveInProgress.value || controller.wanReceiving.value

    Card(modifier = Modifier.fillMaxWidth().clickable { details = true }) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lan || internet) {
                    PulsingDot(MaterialTheme.colorScheme.primary)
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Text(
                    stringResource(
                        when {
                            receiving -> R.string.home_receiving
                            lan -> R.string.home_ready
                            internet -> R.string.home_ready_internet
                            else -> R.string.home_not_ready
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (controller.receiveInProgress.value) {
                LinearProgressIndicator(progress = { controller.receiveProgress.value }, modifier = Modifier.fillMaxWidth())
            } else if (controller.wanReceiving.value) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (!lan && internet) {
                Text(stringResource(R.string.home_wifi_off), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openWifiSettings(context) }) { Text(stringResource(R.string.home_wifi_settings)) }
            }
        }
    }

    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(stringResource(R.string.ob_this_device, controller.deviceName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (controller.reachableAddresses.isEmpty()) {
                        Text(stringResource(R.string.home_no_addresses), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.rec_reachable_title), style = MaterialTheme.typography.titleSmall)
                        controller.reachableAddresses.forEach { address ->
                            Text(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(stringResource(R.string.rec_reachable_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (internet) controller.wanReceiveStatus.value.ifEmpty { stringResource(R.string.rec_wan_on) }
                        else stringResource(R.string.rec_wan_off),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.pair_ok)) } },
        )
    }
}

private fun openWifiSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }
    runCatching { context.startActivity(intent) }
}

/** A send running behind Home, one tap from the transfer screen. */
@Composable
private fun ActiveSendCard(controller: TransferController) {
    val batch = controller.batch.value ?: return
    if (controller.transferVisible.value) return
    Card(
        modifier = Modifier.fillMaxWidth().clickable { controller.transferVisible.value = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (batch.active) R.string.home_sending else R.string.home_send_done),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                batch.legs.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (batch.active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Content picked (or shared in) but not sent yet, for when the Send-to sheet was dismissed. */
@Composable
private fun StagedCard(controller: TransferController) {
    val files = controller.pendingShare
    if (files.isEmpty() || controller.sendToOpen.value || controller.batch.value != null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.home_staged, files.size, formatSize(files.sumOf { it.size })),
                style = MaterialTheme.typography.titleSmall,
            )
            files.take(3).forEach { f ->
                Text("• ${f.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.sendToOpen.value = true }) { Text(stringResource(R.string.home_choose_device)) }
                TextButton(onClick = { controller.clearPendingShare() }) { Text(stringResource(R.string.send_clear)) }
            }
        }
    }
}

/** Text to send, prefilled from the clipboard when it holds text (plan section 5). */
@Composable
private fun TextDropDialog(controller: TransferController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clip = remember { clipboardText(context) }
    var text by rememberSaveable { mutableStateOf(clip.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_text_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!clip.isNullOrEmpty() && text == clip) {
                    Text(stringResource(R.string.home_text_from_clipboard), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                controller.stageText(text)
                onDismiss()
            }, enabled = text.isNotBlank()) { Text(stringResource(R.string.home_text_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pair_cancel)) } },
    )
}

private fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching null
    val clip = cm.primaryClip ?: return@runCatching null
    if (clip.itemCount == 0) return@runCatching null
    clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()

@Composable
internal fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "listening")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(path),
    pathFillType = PathFillType.EvenOdd,
    fill = SolidColor(Color.Black),
).build()

// Material icons (Apache 2.0); material-icons-core doesn't include these.
private val FileIcon = icon(
    "InsertDriveFile",
    "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6z M13,9V3.5L18.5,9H13z",
)
private val PhotosIcon = icon(
    "PhotoLibrary",
    "M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2z " +
        "M11,12l2.03,2.71L16,11l4,5H8l3,-4z M2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z",
)
private val TextIcon = icon(
    "Notes",
    "M3,18h12v-2H3v2z M3,6v2h18V6H3z M3,13h18v-2H3v2z",
)
