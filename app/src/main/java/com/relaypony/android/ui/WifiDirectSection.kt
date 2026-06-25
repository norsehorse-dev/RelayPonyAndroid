package com.relaypony.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController
import com.relaypony.android.transfer.UiText
import com.relaypony.android.transfer.resolve

/**
 * Shared, collapsible "connect directly" panel for both Send and Receive. Collapsed by default so
 * it doesn't crowd the primary same-Wi-Fi flow; expanded it exposes Wi-Fi Direct discovery and
 * connection. [asSender] only changes which side this device arms as.
 */
@Composable
fun WifiDirectSection(controller: TransferController, asSender: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val unnamedDevice = stringResource(R.string.wd_unnamed)
    val wifiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) controller.wifiDirect.discover()
        else controller.wifiDirect.lastError.value = UiText(R.string.wd_perm_msg)
    }
    val sharing = controller.pendingShare.isNotEmpty()
    // A sender must pick files before arming; a receiver can arm immediately. Disabling (rather than
    // hiding) keeps the panel's shape and shows a "pick files first" hint in place of the old test path.
    val armEnabled = !asSender || sharing

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.wd_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.wd_subtitle), style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) stringResource(R.string.wd_collapse) else stringResource(R.string.wd_expand),
                )
            }

            if (expanded) {
                Text(stringResource(R.string.wd_state, controller.wifiDirect.connectionState.value.resolve()), style = MaterialTheme.typography.bodySmall)
                controller.wifiDirect.groupOwnerAddress.value?.let {
                    Text(stringResource(R.string.wd_group_owner, it), style = MaterialTheme.typography.bodySmall)
                }
                controller.wifiDirect.lastError.value?.let {
                    Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.wd_transfer, controller.wifiTransferStatus.value.resolve()), style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { wifiPermLauncher.launch(controller.wifiDirectPermissions()) }) {
                        Text(stringResource(R.string.wd_find))
                    }
                    OutlinedButton(onClick = { controller.wifiDirect.disconnect() }) { Text(stringResource(R.string.wd_disconnect)) }
                }
                Button(
                    onClick = { controller.armWifiDirect(asSender = asSender) },
                    enabled = armEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (asSender) {
                            if (sharing) stringResource(R.string.wd_arm_send_n, controller.pendingShare.size)
                            else stringResource(R.string.wd_arm_send_pick)
                        } else {
                            stringResource(R.string.wd_arm_receive)
                        }
                    )
                }

                controller.wifiDirect.p2pPeers.forEach { device ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    device.deviceName.ifEmpty { unnamedDevice },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(device.deviceAddress, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { controller.wifiDirect.connect(device) }) { Text(stringResource(R.string.wd_connect)) }
                        }
                    }
                }
            }
        }
    }
}
