package com.relaypony.session.inbox

import kotlinx.serialization.Serializable

/**
 * A record of one file this device received. [localPath] points at the file written into the app's
 * private storage; [savedToDownloads] tracks whether it was also copied to public Downloads
 * (Phase 5b-ii).
 */
@Serializable
data class ReceivedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val fromDevice: String,
    val receivedAtEpochMs: Long,
    val localPath: String,
    val savedToDownloads: Boolean = false,
)
