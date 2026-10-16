package com.relaypony.android

import android.content.Context
import android.content.Intent
import com.relaypony.android.transfer.TransferController

/**
 * Release stub for the debug-only store-screenshot demo mode (see the debug source set). Same
 * API, does nothing, so no demo data or hooks ship.
 */
object Demo {
    val active: Boolean get() = false
    val deviceName: String? get() = null
    val startTab: Int get() = 0

    @Suppress("UNUSED_PARAMETER")
    fun handle(context: Context, intent: Intent?): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun show(controller: TransferController, screen: String) = Unit
}
