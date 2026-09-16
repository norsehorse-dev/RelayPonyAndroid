package com.relaypony.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relaypony.session.wan.WanDirectDevController

/**
 * Minimal WAN-direct dev screen for the spike. Wire it from anywhere you can supply this device's
 * age scalar and handle, e.g.:
 *
 *   val id = com.agepony.core.recipients.X25519Identity(storedAgeSecret)
 *   WanDirectDevScreen(id.privateKey, com.agepony.core.recipients.X25519Recipient(id.publicKey).toBech32())
 */
@Composable
fun WanDirectDevScreen(myScalar: ByteArray, myHandle: String) {
    val controller = remember { WanDirectDevController(myScalar, myHandle) }
    var peerHandle by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") }
    var incoming by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val outgoing = remember { mutableStateListOf<String>() }
    val received = remember { mutableStateListOf<String>() }

    DisposableEffect(controller) {
        controller.onStatus = { status = it }
        controller.onOutgoing = { outgoing.add(it) }
        controller.onReceived = { received.add(it) }
        onDispose {
            controller.onStatus = {}
            controller.onOutgoing = {}
            controller.onReceived = {}
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("This device", style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(myHandle, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(peerHandle, { peerHandle = it }, label = { Text("Peer handle (age1...)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ controller.openPath(peerHandle) }) { Text("Open path") }

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)

        if (outgoing.isNotEmpty()) {
            Text("Send these to your peer", style = MaterialTheme.typography.titleMedium)
            outgoing.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }

        OutlinedTextField(incoming, { incoming = it }, label = { Text("Paste blob from peer") },
            modifier = Modifier.fillMaxWidth())
        Button({ runCatching { controller.importIncoming(incoming) }; incoming = "" }) { Text("Import") }

        OutlinedTextField(message, { message = it }, label = { Text("Message") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ controller.send(peerHandle, message); message = "" }) { Text("Send") }

        if (received.isNotEmpty()) {
            Text("Received", style = MaterialTheme.typography.titleMedium)
            received.forEach { SelectionContainer { Text(it) } }
        }
    }
}
