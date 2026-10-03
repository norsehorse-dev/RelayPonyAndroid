package com.relaypony.android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/** 4.0 navigation (plan section 4.1): Send and Received. Receiving runs whenever the app is open. */
private enum class Tab(@StringRes val titleRes: Int, val icon: ImageVector) {
    Send(R.string.nav_send, Icons.Filled.Share),
    Received(R.string.nav_received, Icons.Filled.Email),
}

/** Screens that cover the tabs, opened from the top bar. */
private enum class Overlay { None, Settings, Advanced }

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
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val showTransfer = controller.transferVisible.value && controller.batch.value != null

    BackHandler(enabled = overlay != Overlay.None) {
        overlay = if (overlay == Overlay.Advanced) Overlay.Settings else Overlay.None
    }
    BackHandler(enabled = overlay == Overlay.None && showTransfer) {
        if (controller.batch.value?.active == true) controller.transferVisible.value = false
        else controller.closeTransfer()
    }

    val snackbar = remember { SnackbarHostState() }
    val notice = controller.notice.value
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            controller.notice.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (overlay) {
                            Overlay.Settings -> stringResource(R.string.settings_title)
                            Overlay.Advanced -> stringResource(R.string.adv_title)
                            Overlay.None -> stringResource(R.string.app_name)
                        },
                    )
                },
                navigationIcon = {
                    if (overlay != Overlay.None) {
                        IconButton(onClick = {
                            overlay = if (overlay == Overlay.Advanced) Overlay.Settings else Overlay.None
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { controller.pair.open() }) {
                        Icon(QrCodeIcon, contentDescription = stringResource(R.string.pair_title))
                    }
                    if (overlay == Overlay.None) {
                        IconButton(onClick = { overlay = Overlay.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (overlay == Overlay.None && !showTransfer) {
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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                overlay == Overlay.Settings -> SettingsScreen(controller, onAdvanced = { overlay = Overlay.Advanced })
                overlay == Overlay.Advanced -> AdvancedScreen(controller)
                showTransfer -> TransferProgressScreen(controller)
                tab == Tab.Send -> HomeScreen(controller)
                else -> InboxScreen(controller)
            }
        }
    }

    SendToSheet(controller)
    PairSheet(controller)
    PairPrompts(controller)
}
