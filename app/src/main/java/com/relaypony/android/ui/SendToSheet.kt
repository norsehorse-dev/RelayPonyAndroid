package com.relaypony.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.PairingController
import com.relaypony.android.transfer.TransferController
import kotlinx.coroutines.delay

/**
 * Choose who gets the picked content (plan section 4.3). Tapping a device sends to it straight
 * away; ticking boxes sends to several at once. Nearby devices go over the LAN, the rest over the
 * internet, with no choice to make.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToSheet(controller: TransferController) {
    if (!controller.sendToOpen.value) return
    val files = controller.pendingShare
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Read both so the list follows discovery and pairing changes.
    val revision = controller.trustRevision.intValue
    val nearbyCount = controller.peers.size
    val targets = remember(revision, nearbyCount, controller.peers.toList()) { controller.sendTargets() }
    val selected = remember { mutableStateListOf<String>().apply { addAll(controller.sendToPreselect.value) } }
    val canScan = rememberHasCamera()
    // Plan section 10: on Wi-Fi for a while with paired devices but none seen nearby, the network
    // may be keeping devices apart (client isolation).
    var lookedAWhile by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(10_000)
        lookedAWhile = true
    }
    val isolationHint = lookedAWhile && targets.isNotEmpty() && targets.none { it.nearby } &&
        controller.reachableAddresses.isNotEmpty()

    ModalBottomSheet(onDismissRequest = { controller.sendToOpen.value = false }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.sendto_title, files.size, formatSize(files.sumOf { it.size })),
                style = MaterialTheme.typography.titleLarge,
            )
            files.take(3).forEach { f ->
                Text("• ${f.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (files.size > 3) {
                Text(stringResource(R.string.sendto_more_files, files.size - 3), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (isolationHint) {
                Text(
                    stringResource(R.string.sendto_isolation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (targets.isEmpty()) {
                Text(stringResource(R.string.sendto_no_devices), style = MaterialTheme.typography.bodyMedium)
            }
            targets.forEach { t ->
                val ticked = t.handle in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            // A plain tap sends; once boxes are ticked, taps add to the selection.
                            if (selected.isEmpty()) controller.sendToDevices(listOf(t.handle))
                            else if (ticked) selected.remove(t.handle) else selected.add(t.handle)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ticked,
                        onCheckedChange = { on -> if (on) selected.add(t.handle) else selected.remove(t.handle) },
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(if (t.nearby) R.string.sendto_nearby else R.string.sendto_internet),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t.nearby) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            TextButton(onClick = {
                controller.sendToOpen.value = false
                controller.pair.open(
                    if (canScan) PairingController.Tab.SCAN else PairingController.Tab.WORD,
                    sendAfter = true,
                )
            }) {
                Icon(QrCodeIcon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.send_pair_new))
            }

            if (selected.isNotEmpty()) {
                Button(
                    onClick = { controller.sendToDevices(selected.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sendto_send_n, selected.size)) }
            } else if (targets.isNotEmpty()) {
                Text(stringResource(R.string.sendto_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
