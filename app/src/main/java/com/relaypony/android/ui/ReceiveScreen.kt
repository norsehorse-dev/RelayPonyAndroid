package com.relaypony.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.QrImage
import com.relaypony.android.transfer.TransferController

@Composable
fun ReceiveScreen(controller: TransferController) {
    val qrBitmap = remember { QrImage.generate(controller.myQrText()) }
    val isTv = rememberIsTelevision()

    // Start listening as soon as the user lands on Receive. startReceiving() is a no-op if already
    // listening, so re-entering the tab is safe.
    LaunchedEffect(Unit) { if (controller.wantsReceiving.value) controller.startReceiving() }

    if (isTv) {
        // TV: a landscape two-pane layout — controls and status on the left, the pairing card on
        // the right with the QR sized to the pane so it is always fully on screen. A single
        // portrait column can never fit a scannable QR plus its context on a 540dp-tall canvas.
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusAndControls(controller)
                ReceiveNote(controller)
                if (controller.wifiDirect.isSupported) {
                    HorizontalDivider()
                    WifiDirectSection(controller, asSender = false)
                }
            }
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                // Reserve ~170dp for the card's texts and padding; whatever height remains goes
                // to the QR, clamped so it stays scannable but never overflows the pane.
                val qrSize = minOf(maxWidth - 80.dp, maxHeight - 170.dp).coerceIn(160.dp, 400.dp)
                PairingCard(controller, qrBitmap, qrSize)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusAndControls(controller)
            PairingCard(controller, qrBitmap, qrSize = 240.dp)
            ReceiveNote(controller)
            if (controller.wifiDirect.isSupported) {
                HorizontalDivider()
                WifiDirectSection(controller, asSender = false)
            }
        }
    }
}

/** Listening state, the start/stop control, live progress, and the last-received banner. */
@Composable
private fun StatusAndControls(controller: TransferController) {
    val status = controller.status.value
    val justReceived = controller.lastStatusKind.value == TransferController.StatusKind.RECEIVED
    val receiving = controller.isReceiving.value

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (receiving) {
            PulsingDot(MaterialTheme.colorScheme.primary)
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                if (receiving) stringResource(R.string.rec_ready_title) else stringResource(R.string.rec_paused_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (receiving) stringResource(R.string.rec_ready_body) else stringResource(R.string.rec_paused_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (receiving) {
        OutlinedButton(onClick = { controller.stopReceiving() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rec_stop))
        }
    } else {
        Button(onClick = { controller.startReceiving() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rec_start))
        }
    }

    if (controller.receiveInProgress.value) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.rec_receiving), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { controller.receiveProgress.value },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // The address, always visible while receiving. When discovery works this is redundant;
    // when it doesn't — a hotspot, an AP that filters broadcast — it is the only way anyone
    // reaches this phone, and hunting for it in Settings is not a reasonable ask.
    if (controller.isReceiving.value && controller.reachableAddresses.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.rec_reachable_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                controller.reachableAddresses.forEach { address ->
                    Text(
                        address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(R.string.rec_reachable_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (justReceived) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun PairingCard(
    controller: TransferController,
    qrBitmap: android.graphics.Bitmap,
    qrSize: Dp,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.rec_pair_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.rec_pair_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.rec_qr_desc),
                modifier = Modifier.size(qrSize),
            )
            Text(stringResource(R.string.ob_this_device, controller.deviceName), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReceiveNote(controller: TransferController) {
    Text(
        if (controller.autoSave.value) {
            stringResource(R.string.rec_note_autosave)
        } else {
            stringResource(R.string.rec_note_manual)
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "listening")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}
