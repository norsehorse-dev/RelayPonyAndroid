package com.relaypony.android.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** TV/leanback detection and small D-pad affordances. RelayPony on a television is primarily a
 *  receiver: the TV shows the pairing QR on the big screen and phones send to it. */
object Tv {

    /** True when running on a television (Google TV / Android TV) ui mode or a leanback-only device. */
    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** True when any camera is present; gates the QR-scan pairing affordances. */
    fun hasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** True when a Storage Access Framework document picker exists. Google TV ships without one,
     *  so ACTION_OPEN_DOCUMENT would land on "no app can perform this action". Needs the matching
     *  <queries> declaration in the manifest for package visibility on API 30+. */
    fun canPickDocuments(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        return intent.resolveActivity(context.packageManager) != null
    }
}

@Composable
fun rememberIsTelevision(): Boolean {
    val context = LocalContext.current
    return remember { Tv.isTelevision(context) }
}

@Composable
fun rememberHasCamera(): Boolean {
    val context = LocalContext.current
    return remember { Tv.hasCamera(context) }
}

@Composable
fun rememberCanPickDocuments(): Boolean {
    val context = LocalContext.current
    return remember { Tv.canPickDocuments(context) }
}

/** A visible focus ring for D-pad navigation. Material's focus state layer is too subtle from ten
 *  feet away; this draws an explicit border around the focused element. */
@Composable
fun Modifier.tvFocusHighlight(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusEvent { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}
