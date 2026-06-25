package com.relaypony.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.relaypony.android.transfer.SharedFiles
import com.relaypony.android.transfer.TransferController
import com.relaypony.android.ui.RelayPonyApp
import com.relaypony.android.ui.theme.RelayPonyTheme
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var controller: TransferController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDefaultLocale()
        enableEdgeToEdge()
        // If launched from another app's share sheet, resolve the shared files up front.
        val sharedFiles = SharedFiles.fromIntent(this, intent)
        setContent {
            val ctrl = remember { TransferController(applicationContext).also { controller = it } }

            // Per-app language WITHOUT recreating the Activity. We wrap the base (Activity) context
            // so startActivity/launchers keep working, but override getResources() to resolve in the
            // chosen locale. Changing the language state recomposes this, re-providing the context
            // and configuration so every stringResource re-resolves — no activity rebuild, no flash.
            val tag = ctrl.languageCode.value
            val base = LocalContext.current
            val localizedCtx: Context = remember(tag, base) {
                if (tag.isEmpty()) {
                    base
                } else {
                    val config = Configuration(base.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(tag))
                    }
                    val localizedResources = base.createConfigurationContext(config).resources
                    object : ContextWrapper(base) {
                        override fun getResources(): Resources = localizedResources
                    }
                }
            }

            val darkTheme = when (ctrl.themeMode.value) {
                TransferController.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                TransferController.ThemeMode.LIGHT -> false
                TransferController.ThemeMode.DARK -> true
            }

            CompositionLocalProvider(
                LocalContext provides localizedCtx,
                LocalResources provides localizedCtx.resources,
                LocalConfiguration provides localizedCtx.resources.configuration,
            ) {
                RelayPonyTheme(darkTheme = darkTheme) {
                    LaunchedEffect(Unit) {
                        if (sharedFiles.isNotEmpty()) ctrl.setPendingShare(sharedFiles)
                    }
                    RelayPonyApp(ctrl)
                }
            }
        }
    }

    /** Align the process default locale (used by DateUtils and other default-locale formatters) with
     *  the persisted in-app language BEFORE any UI composes, so non-Compose formatters don't render in
     *  a stale system / per-app OS locale. Runtime changes are handled in TransferController.setLanguage. */
    private fun applyDefaultLocale() {
        val code = getSharedPreferences("relaypony_settings", Context.MODE_PRIVATE)
            .getString("lang", "en") ?: "en"
        val locale = if (code.isNotEmpty()) {
            Locale.forLanguageTag(code)
        } else {
            val cfg = Resources.getSystem().configuration
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) cfg.locales[0] else cfg.locale
        }
        Locale.setDefault(locale)
    }

    override fun onDestroy() {
        controller?.stop()
        super.onDestroy()
    }
}
