package com.relaypony.android.transfer

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A deferred, locale-independent string: a string resource id plus optional format args, resolved to
 * text only at display time via [resolve]. Holding this in state instead of a pre-resolved String
 * keeps status fields correct when the in-app language changes without an activity recreation, and
 * stops the application context's locale (which can differ from the in-app choice) from leaking an
 * already-resolved string into a differently-localized UI.
 */
data class UiText(@StringRes val id: Int, val args: List<Any?>) {
    constructor(@StringRes id: Int, vararg args: Any?) : this(id, args.toList())
}

@Composable
fun UiText.resolve(): String {
    if (args.isEmpty()) return stringResource(id)
    val out = ArrayList<Any>(args.size)
    for (a in args) out.add((if (a is UiText) a.resolve() else a) ?: "")
    return stringResource(id, *out.toTypedArray())
}
