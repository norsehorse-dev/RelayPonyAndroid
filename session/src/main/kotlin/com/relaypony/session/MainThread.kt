package com.relaypony.session

/**
 * Where the WAN layer runs its bookkeeping and callbacks. Android passes one backed by the main
 * Looper; RelayPony Desktop passes one on the UI event thread. Kept as a plain interface so this
 * module builds and tests on a plain JDK with no Android classes.
 */
interface MainThread {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, delayMs: Long)
    fun removeCallbacks(r: Runnable)
}
