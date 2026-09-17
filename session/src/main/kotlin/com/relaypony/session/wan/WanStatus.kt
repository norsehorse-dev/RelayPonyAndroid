package com.relaypony.session.wan

/**
 * A localization-agnostic status emitted by [WanTransfer]. The session module has no access to the
 * app's string resources, so it reports a [kind] (plus any argument), and the app layer maps it to a
 * localized, in-app-language string. This keeps every user-facing translation in the app's
 * strings.xml and out of this pure-logic module.
 */
enum class WanStatusKind {
    READY,
    IN_PROGRESS,
    ADD_FILES,
    PREPARING,
    PREPARE_FAILED,
    CONNECTING,
    SENDING,
    SENDING_RELAY,
    SENT,
    SEND_FAILED,
    RECEIVING,
    RECEIVING_RELAY,
    RECEIVED,
    RECEIVE_FAILED,
}

/**
 * @param arg   an optional string argument (an error message, or the sender's name for RECEIVED).
 * @param count an optional numeric argument (the received file count for RECEIVED).
 */
data class WanStatus(
    val kind: WanStatusKind,
    val arg: String? = null,
    val count: Int = 0,
)
