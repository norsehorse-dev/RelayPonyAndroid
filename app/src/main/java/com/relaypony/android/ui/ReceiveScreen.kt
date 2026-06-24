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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.QrImage
import com.relaypony.android.transfer.TransferController

@Composable
fun ReceiveScreen(controller: TransferController) {
    val qrBitmap = remember { QrImage.generate(controller.myQrText()) }

    // Start listening as soon as the user lands on Receive. startReceiving() is a no-op if already
    // listening, so re-entering the tab is safe.
    LaunchedEffect(Unit) { if (controller.wantsReceiving.value) controller.startReceiving() }

    val status = controller.status.value
    val justReceived = controller.lastStatusKind.value == TransferController.StatusKind.RECEIVED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                    modifier = Modifier.size(240.dp),
                )
                Text(stringResource(R.string.ob_this_device, controller.deviceName), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            if (controller.autoSave.value) {
                stringResource(R.string.rec_note_autosave)
            } else {
                stringResource(R.string.rec_note_manual)
            },
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        WifiDirectSection(controller, asSender = false)
    }
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
