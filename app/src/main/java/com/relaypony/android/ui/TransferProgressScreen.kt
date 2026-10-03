package com.relaypony.android.ui

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.LegState
import com.relaypony.android.transfer.PairingController
import com.relaypony.android.transfer.SendLeg
import com.relaypony.android.transfer.SendRoute
import com.relaypony.android.transfer.TransferController

/**
 * The transfer screen (plan section 4.4): the files, each device with its route and progress, and
 * Stop. When everything has finished: Done, or Send more to the same devices.
 */
@Composable
fun TransferProgressScreen(controller: TransferController) {
    val batch = controller.batch.value ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(
                when {
                    batch.active -> R.string.xfer_sending
                    batch.allSent -> R.string.xfer_sent
                    else -> R.string.xfer_finished
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.xfer_files, batch.fileNames.size, formatSize(batch.totalBytes)),
                style = MaterialTheme.typography.titleSmall,
            )
            batch.fileNames.take(5).forEach { name ->
                Text("• $name", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (batch.fileNames.size > 5) {
                Text(stringResource(R.string.sendto_more_files, batch.fileNames.size - 5), style = MaterialTheme.typography.bodySmall)
            }
        }

        batch.legs.forEach { leg ->
            LegCard(
                leg,
                onRetry = { controller.retryLeg(leg.handle) },
                onPair = { controller.pair.open(PairingController.Tab.QR) },
            )
        }

        if (batch.active) {
            OutlinedButton(onClick = { controller.stopSending() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.xfer_stop))
            }
            TextButton(onClick = { controller.transferVisible.value = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.xfer_hide))
            }
        } else {
            Button(onClick = { controller.closeTransfer() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_done))
            }
            OutlinedButton(onClick = { controller.sendMore() }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (batch.legs.size == 1) stringResource(R.string.xfer_send_more_one, batch.legs.first().name)
                    else stringResource(R.string.xfer_send_more)
                )
            }
        }
    }
}

@Composable
private fun LegCard(leg: SendLeg, onRetry: () -> Unit, onPair: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    leg.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (leg.state) {
                        LegState.CONNECTING -> stringResource(R.string.xfer_connecting)
                        LegState.SENDING -> leg.progress?.let { stringResource(R.string.xfer_percent, (it * 100).toInt()) }
                            ?: stringResource(R.string.xfer_in_progress)
                        LegState.SENT -> stringResource(R.string.xfer_leg_sent)
                        LegState.FAILED -> stringResource(R.string.xfer_leg_failed)
                        LegState.CANCELLED -> stringResource(R.string.xfer_leg_stopped)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when (leg.state) {
                        LegState.SENT -> MaterialTheme.colorScheme.primary
                        LegState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                stringResource(
                    when (leg.route) {
                        SendRoute.NEARBY -> R.string.xfer_route_nearby
                        SendRoute.INTERNET -> R.string.xfer_route_internet
                        SendRoute.INTERNET_DIRECT -> R.string.xfer_route_direct
                        SendRoute.INTERNET_RELAY -> R.string.xfer_route_relay
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (leg.active) {
                val p = leg.progress
                if (p == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (leg.state == LegState.FAILED && leg.refused) {
                // It answered, but doesn't have this device paired (PROTOCOL_v3 section 9.3).
                Text(leg.error ?: stringResource(R.string.xfer_refused, leg.name), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPair) { Text(stringResource(R.string.send_pair_peer)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.pair_try_again)) }
                }
            } else if (leg.state == LegState.FAILED) {
                Text(stringResource(R.string.xfer_unreachable, leg.name), style = MaterialTheme.typography.bodySmall)
                if (leg.route != SendRoute.NEARBY) {
                    Text(stringResource(R.string.xfer_relay_hint), style = MaterialTheme.typography.bodySmall)
                }
                leg.error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.pair_try_again)) }
            }
        }
    }
}
