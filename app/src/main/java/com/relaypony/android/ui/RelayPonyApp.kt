package com.relaypony.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.relaypony.android.R
import com.relaypony.android.transfer.TransferController

private enum class Tab(@StringRes val titleRes: Int, val icon: ImageVector) {
    Send(R.string.nav_send, Icons.Filled.Share),
    Receive(R.string.nav_receive, Icons.Filled.Lock),
    Inbox(R.string.nav_inbox, Icons.Filled.Email),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayPonyApp(controller: TransferController) {
    if (controller.showOnboarding.value) {
        OnboardingFlow(controller, onFinish = { controller.finishOnboarding() })
        return
    }

    // The Wi-Fi Direct broadcast receiver stays live for the whole app session.
    DisposableEffect(Unit) {
        controller.wifiDirect.register()
        onDispose { controller.wifiDirect.unregister() }
    }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex]
    var inSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inSettings) stringResource(R.string.settings_title)
                        else "RelayPony \u00b7 ${stringResource(tab.titleRes)}"
                    )
                },
                actions = {
                    IconButton(onClick = { inSettings = !inSettings }) {
                        if (inSettings) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_title))
                        } else {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!inSettings) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tabIndex = t.ordinal },
                            icon = { Icon(t.icon, contentDescription = stringResource(t.titleRes)) },
                            label = { Text(stringResource(t.titleRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                inSettings -> SettingsScreen(controller)
                tab == Tab.Send -> SendScreen(controller)
                tab == Tab.Receive -> ReceiveScreen(controller)
                else -> InboxScreen(controller)
            }
        }
    }
}
