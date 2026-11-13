package com.relaypony.android.transfer

import android.os.Handler
import android.os.Looper
import com.relaypony.session.MainThread

/** The session module's [MainThread] on Android: the main Looper, as WanTransfer used directly in 4.0.0. */
class LooperMainThread : MainThread {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(r: Runnable) { handler.post(r) }
    override fun postDelayed(r: Runnable, delayMs: Long) { handler.postDelayed(r, delayMs) }
    override fun removeCallbacks(r: Runnable) { handler.removeCallbacks(r) }
}
