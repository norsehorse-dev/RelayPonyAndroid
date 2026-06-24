package com.relaypony.session

import kotlinx.serialization.Serializable

/**
 * One file in a transfer. The manifest is encrypted on the wire (it travels inside a MANIFEST
 * frame whose payload is provider ciphertext), so these filenames never appear in plaintext to a
 * network observer.
 */
@Serializable
data class FileEntry(
    val name: String,
    val size: Long,
    val mime: String,
)

/** The set of files in a transfer, sent encrypted ahead of the file bodies. */
@Serializable
data class Manifest(
    val files: List<FileEntry>,
)
