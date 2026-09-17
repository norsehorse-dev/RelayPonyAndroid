package com.relaypony.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.relaypony.session.wan.WanDirectDevController

/**
 * Guided WAN-direct dev screen over the relay. Both devices enter each other's handle and tap
 * Connect; the RelayPony relay brokers the handshake automatically (no copy/paste), then a 1 MB
 * stream test proves the punched path.
 */
@Composable
fun WanDirectDevScreen(myScalar: ByteArray, myHandle: String) {
    val controller = remember { WanDirectDevController(myScalar, myHandle) }
    var peerHandle by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") }
    var message by remember { mutableStateOf("") }
    val received = remember { mutableStateListOf<String>() }
    var streamSent by remember { mutableStateOf("") }
    var streamRecv by remember { mutableStateOf("") }
    var diag by remember { mutableStateOf("") }
    var relayOn by remember { mutableStateOf(false) }
    var relaySent by remember { mutableStateOf("") }
    var relayRecv by remember { mutableStateOf("") }
    var usingRelay by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        controller.onStatus = { status = it }
        controller.onReceived = { received.add(it) }
        controller.onStreamSent = { streamSent = it }
        controller.onStreamRecv = { streamRecv = it }
        controller.onDiag = { diag = it }
        controller.onRelaySent = { relaySent = it }
        controller.onRelayRecv = { relayRecv = it }
        controller.onUsingRelay = { usingRelay = it; if (it) relayOn = true }
        onDispose {
            controller.onStatus = {}
            controller.onReceived = {}
            controller.onStreamSent = {}
            controller.onStreamRecv = {}
            controller.onDiag = {}
            controller.onRelaySent = {}
            controller.onRelayRecv = {}
            controller.onUsingRelay = {}
            controller.stop()
        }
    }

    val clipboard = LocalClipboardManager.current
    val connected = status.equals("connected", ignoreCase = true)
    val statusText = when {
        connected -> "Connected"
        usingRelay -> "Relay fallback ready"
        status.lowercase() == "gathering" -> "Connecting…"
        status.lowercase() == "punching" -> "Linking up…"
        status.lowercase() == "failed" -> "Couldn’t connect"
        status.lowercase() == "idle" -> "Not connected"
        else -> status
    }
    fun paste(): String = clipboard.getText()?.text?.trim() ?: ""

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (connected) Color(0xFF2E7D32) else Color(0xFFE68A00))
                )
                Text(statusText, style = MaterialTheme.typography.titleSmall)
            }
        }

        if (!connected) {
            Text(
                "Do steps 1 and 2 on BOTH phones, then tap Connect on both. They find each other through the relay — no codes to copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Step(1, "Your handle") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(myHandle, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton({ clipboard.setText(AnnotatedString(myHandle)) }) { Text("Copy") }
            }
            Text(
                "Send this to the other phone once, so each side can enter the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Step(2, "The other phone’s handle") {
            OutlinedTextField(
                peerHandle, { peerHandle = it }, label = { Text("age1…") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton({ val p = paste(); if (p.isNotEmpty()) peerHandle = p }) { Text("Paste") }
        }

        if (!connected) {
            Step(3, "Connect") {
                Button(
                    { controller.openPath(peerHandle) },
                    enabled = peerHandle.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }
        }

        if (!connected && diag.isNotEmpty()) {
            Step(null, "Diagnostics") {
                SelectionContainer {
                    Text(diag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!connected) {
            Step(null, "Relay fallback") {
                Text(
                    "If the direct connection won\u2019t punch through (e.g. cellular), send through the relay instead. Tap Enable on BOTH phones, then Send on one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    { controller.enableRelayFallback(peerHandle); relayOn = true },
                    enabled = !relayOn && peerHandle.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (relayOn) "Relay fallback on" else "Enable relay fallback") }
                Button(
                    { controller.sendStreamViaRelay(peerHandle); relayOn = true },
                    enabled = peerHandle.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send 1 MB via relay") }
                if (relaySent.isNotEmpty()) Text(relaySent, style = MaterialTheme.typography.bodySmall)
                if (relayRecv.isNotEmpty()) Text(relayRecv, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (connected) {
            Step(null, "Send a message") {
                OutlinedTextField(
                    message, { message = it }, label = { Text("Message") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button({ controller.send(peerHandle, message); message = "" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Send")
                }
            }
            Step(null, "Big file test") {
                Button(
                    { controller.sendStreamTest(peerHandle) },
                    enabled = streamSent.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (streamSent.isEmpty()) "Send 1 MB test" else "1 MB test sent")
                }
                if (streamSent.isNotEmpty()) Text(streamSent, style = MaterialTheme.typography.bodySmall)
                if (streamRecv.isNotEmpty()) Text(streamRecv, style = MaterialTheme.typography.bodySmall)
            }
            if (received.isNotEmpty()) {
                Step(null, "Received") {
                    received.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int?, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (n != null) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "$n",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}
