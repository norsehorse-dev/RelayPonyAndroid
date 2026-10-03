package com.relaypony.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.relaypony.android.R
import com.relaypony.android.transfer.PairingController
import com.relaypony.android.transfer.PairingController.Prompt
import com.relaypony.android.transfer.PairingController.WordCode
import com.relaypony.android.transfer.QrImage
import com.relaypony.android.transfer.TransferController
import com.relaypony.android.transfer.resolve
import com.relaypony.session.pairing.SasV2
import kotlinx.coroutines.delay
import java.util.Locale

/** The pair icon (Material "qr_code", Apache 2.0). material-icons-core doesn't include it. */
val QrCodeIcon: ImageVector = ImageVector.Builder(
    name = "QrCode",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M3,11h8V3H3V11z M5,5h4v4H5V5z M3,21h8v-8H3V21z M5,15h4v4H5V15z M13,3v8h8V3H13z " +
            "M19,9h-4V5h4V9z M19,19h2v2h-2V19z M13,13h2v2h-2V13z M15,15h2v2h-2V15z M13,17h2v2h-2V17z " +
            "M15,19h2v2h-2V19z M17,17h2v2h-2V17z M17,13h2v2h-2V13z M19,15h2v2h-2V15z",
    ),
    pathFillType = PathFillType.EvenOdd,
    fill = SolidColor(Color.Black),
).build()

/**
 * The pair sheet (plan section 6.3): QR code, Scan, Word code. Opened from the pair icon on every
 * screen, and from Send with files staged, in which case the transfer starts once pairing ends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairSheet(controller: TransferController) {
    val pair = controller.pair
    if (!pair.sheetOpen.value) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DisposableEffect(Unit) {
        pair.surfaceVisible(true)
        onDispose { pair.surfaceVisible(false) }
    }
    ModalBottomSheet(onDismissRequest = { pair.close() }, sheetState = sheetState) {
        Text(
            stringResource(R.string.pair_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        val tabs = PairingController.Tab.entries
        PrimaryTabRow(selectedTabIndex = pair.tab.value.ordinal) {
            tabs.forEach { t ->
                Tab(
                    selected = pair.tab.value == t,
                    onClick = { pair.tab.value = t },
                    text = { Text(stringResource(tabLabel(t))) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (pair.tab.value) {
                PairingController.Tab.QR -> PairQrPanel(controller)
                PairingController.Tab.SCAN -> PairScanPanel(controller)
                PairingController.Tab.WORD -> WordCodePanel(controller)
            }
        }
    }
}

private fun tabLabel(t: PairingController.Tab): Int = when (t) {
    PairingController.Tab.QR -> R.string.pair_tab_qr
    PairingController.Tab.SCAN -> R.string.pair_tab_scan
    PairingController.Tab.WORD -> R.string.pair_tab_word
}

/**
 * This device's pairing QR (v2, section 3), refreshed when it expires or is used, with the time
 * left and a way to show the older one-way QR for a device still on RelayPony 3.
 */
@Composable
fun PairQrPanel(controller: TransferController, maxQr: Dp = 280.dp) {
    val pair = controller.pair
    var legacy by rememberSaveable { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            pair.ensureQr()
            remaining = pair.qrRemainingMs()
            delay(1000)
        }
    }
    val text = if (legacy) controller.myQrText() else pair.qr.value?.encode()
    val bitmap = remember(text) { text?.let { QrImage.generate(it) } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(if (legacy) R.string.pair_qr_legacy_body else R.string.pair_qr_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val size = if (maxWidth < maxQr) maxWidth else maxQr
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.rec_qr_desc),
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(8.dp),
                )
            } else {
                CircularProgressIndicator()
            }
        }
        Text(
            stringResource(R.string.ob_this_device, controller.deviceName),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!legacy) {
            val secs = (remaining + 999) / 1000
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pair_qr_expires, String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60)),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { pair.refreshQr() }) { Text(stringResource(R.string.pair_qr_refresh)) }
            }
        }
        TextButton(onClick = { legacy = !legacy }) {
            Text(
                stringResource(if (legacy) R.string.pair_qr_show_new else R.string.pair_qr_show_legacy),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The camera, with the permission asked for here and a way out to word codes (plan section 10). */
@Composable
private fun PairScanPanel(controller: TransferController) {
    val pair = controller.pair
    val context = LocalContext.current
    if (!rememberHasCamera()) {
        Text(stringResource(R.string.pair_no_camera), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { pair.tab.value = PairingController.Tab.WORD }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pair_use_word))
        }
        return
    }

    fun cameraGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(cameraGranted()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    // Coming back from the system settings screen with the camera now allowed.
    OnResume { if (!granted && cameraGranted()) granted = true }

    when {
        granted -> CameraScanner(paused = pair.prompt.value != null, onResult = { pair.onScanned(it) })
        denied -> {
            Text(stringResource(R.string.pair_cam_denied), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pair_cam_settings)) }
            OutlinedButton(onClick = { pair.tab.value = PairingController.Tab.WORD }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_use_word))
            }
        }
        else -> {
            Text(stringResource(R.string.pair_cam_why), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_cam_allow))
            }
            TextButton(onClick = { pair.tab.value = PairingController.Tab.WORD }) {
                Text(stringResource(R.string.pair_use_word))
            }
        }
    }
}

/** An embedded ZXing viewfinder that reports each QR it reads, paused while a prompt is up. */
@Composable
private fun CameraScanner(paused: Boolean, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val statusText = stringResource(R.string.send_scan_prompt)
    val latestOnResult by rememberUpdatedState(onResult)
    val isPaused by rememberUpdatedState(paused)
    val activity = remember(context) { context.findActivity() }
    val view = remember {
        DecoratedBarcodeView(activity ?: context).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText(statusText)
            var lastText: String? = null
            var lastAt = 0L
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    val text = result.text ?: return
                    val now = System.currentTimeMillis()
                    // The same code stays in view for many frames; act on it once every few seconds.
                    if (text == lastText && now - lastAt < RESCAN_MS) return
                    lastText = text
                    lastAt = now
                    if (!isPaused) latestOnResult(text)
                }

                override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
            })
        }
    }
    DisposableEffect(view, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        // Adding the observer replays ON_RESUME if the activity is already resumed.
        if (activity != null) activity.lifecycle.addObserver(observer) else view.resume()
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            view.pause()
        }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
}

private const val RESCAN_MS = 3000L

/** Runs [action] each time the activity resumes. */
@Composable
private fun OnResume(action: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(action)
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
}

private fun Context.findActivity(): ComponentActivity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/** Word codes (section 7): show one, or type one from the other device. */
@Composable
private fun WordCodePanel(controller: TransferController) {
    val pair = controller.pair
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var relay by rememberSaveable { mutableStateOf("") }
    var showRelay by rememberSaveable { mutableStateOf(false) }

    when (val state = pair.word.value) {
        WordCode.Working -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.pair_word_working), style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = { pair.cancelWordCode() }) { Text(stringResource(R.string.pair_cancel)) }
        }
        is WordCode.Showing -> {
            Text(stringResource(R.string.pair_word_your_code), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    state.code,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.relay.isNotEmpty()) {
                Text(
                    stringResource(R.string.pair_word_on_relay, state.relay.substringAfter("://")),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.pair_word_show_hint), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyToClipboard(context, state.code) }) { Text(stringResource(R.string.pair_copy)) }
                TextButton(onClick = { pair.cancelWordCode() }) { Text(stringResource(R.string.pair_cancel)) }
            }
            Text(stringResource(R.string.pair_word_waiting), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is WordCode.Failed -> {
            Text(state.text.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Button(onClick = { pair.resetWordCode() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_try_again))
            }
        }
        null -> {
            Text(stringResource(R.string.pair_word_intro), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { pair.showWordCode() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_word_show))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.pair_word_enter_label), style = MaterialTheme.typography.titleSmall)
            val ready = code.isNotBlank()
            // No IME restrictions and no secure flag: a password manager's keyboard or autofill
            // must be able to type the code (plan section 7.3).
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                label = { Text(stringResource(R.string.pair_word_field)) },
                placeholder = { Text(stringResource(R.string.pair_word_placeholder)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (ready) pair.enterWordCode(code, relay) }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showRelay) {
                OutlinedTextField(
                    value = relay,
                    onValueChange = { relay = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pair_word_relay_field)) },
                    placeholder = { Text("relay.example.com") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { showRelay = true }) { Text(stringResource(R.string.pair_word_relay_toggle)) }
            }
            Button(onClick = { pair.enterWordCode(code, relay) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_word_pair))
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("RelayPony", text))
}

/**
 * The pairing prompts (section 4.3): the code comparison on both devices and how it ended. Shown
 * at the app level, so a request that arrives after the sheet closed still gets answered.
 */
@Composable
fun PairPrompts(controller: TransferController) {
    val pair = controller.pair
    when (val p = pair.prompt.value) {
        null -> Unit
        is Prompt.Confirm -> {
            val name = p.pending.request.name
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.pair_confirm_title, name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SasCode(p.pending.code)
                        Text(stringResource(R.string.pair_confirm_hint, name), style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { Button(onClick = { pair.answer(true) }) { Text(stringResource(R.string.pair_confirm_yes)) } },
                dismissButton = { TextButton(onClick = { pair.answer(false) }) { Text(stringResource(R.string.pair_cancel)) } },
            )
        }
        is Prompt.Waiting -> AlertDialog(
            onDismissRequest = { pair.cancelWaiting() },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.pair_waiting_title, p.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SasCode(p.code)
                    Text(stringResource(R.string.pair_waiting_hint, p.name), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { pair.cancelWaiting() }) { Text(stringResource(R.string.pair_cancel)) } },
        )
        is Prompt.Paired -> AlertDialog(
            onDismissRequest = { pair.dismissPrompt() },
            title = { Text(stringResource(R.string.pair_done_title, p.name)) },
            text = {
                Text(
                    when {
                        p.oneWay -> stringResource(R.string.pair_done_one_way, p.name)
                        p.sending -> stringResource(R.string.pair_done_sending)
                        else -> stringResource(R.string.pair_done_body)
                    },
                )
            },
            confirmButton = { Button(onClick = { pair.dismissPrompt() }) { Text(stringResource(R.string.pair_done)) } },
        )
        is Prompt.Declined -> MessageDialog(
            if (p.name.isEmpty()) stringResource(R.string.pair_declined_unnamed) else stringResource(R.string.pair_declined, p.name),
        ) { pair.dismissPrompt() }
        is Prompt.NoAnswer -> MessageDialog(stringResource(R.string.pair_no_answer, p.name)) { pair.dismissPrompt() }
        is Prompt.Problem -> MessageDialog(p.text.resolve()) { pair.dismissPrompt() }
    }
}

/** The 8-digit comparison code, shown as "4821 0937". */
@Composable
private fun SasCode(code: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            SasV2.display(code),
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pair_ok)) } },
    )
}
