package com.relaypony.session

import com.agepony.core.Age
import com.agepony.core.recipients.ScryptIdentity
import com.agepony.core.recipients.ScryptRecipient
import com.relaypony.session.pairing.PinnedDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * D5 — identity export / import, shared by every Kotlin RelayPony. The Android app and the desktop
 * app compile this same object, so a backup written on one imports byte-for-byte on the other; the
 * iOS build mirrors the format (same age scrypt wrapping, same JSON envelope).
 *
 * A backup bundles this device's age keypair plus its paired-devices list into a single
 * passphrase-protected file. Because the age HANDLE travels with the secret key, peers who already
 * pinned you stay valid on the new device.
 *
 * The wrapping is age's own scrypt (passphrase) recipient — the same primitive `age -p` uses — so a
 * `.age` backup is a real, spec-compliant age file. This talks to the age core directly rather than
 * through RelayPony's [com.relaypony.crypto.CryptoProvider] seam on purpose: that seam models
 * channel trust (X25519 recipients pinned out-of-band) and deliberately has no passphrase mode.
 * Backups are a local, at-rest concern.
 *
 * [FORMAT_VERSION] lets a future build carry a richer identity (e.g. a hybrid post-quantum keypair)
 * without breaking older backups.
 */
object IdentityBackup {

    const val FORMAT_VERSION = 1

    @Serializable
    private data class Envelope(
        val version: Int = FORMAT_VERSION,
        val identity: String,               // AGE-SECRET-KEY-1…
        val devices: List<Dev>,
    )

    @Serializable
    private data class Dev(val handle: String, val name: String, val pinnedAtEpochMs: Long)

    /** The decoded contents of a backup, ready for a controller to apply. */
    data class Imported(val identitySecret: String, val devices: List<PinnedDevice>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val envelopeSerializer = Envelope.serializer()

    /** Serialize {identity, devices} to JSON and scrypt-encrypt it to [passphrase], streamed to [out]. */
    fun export(passphrase: String, identitySecret: String, devices: List<PinnedDevice>, out: OutputStream) {
        val envelope = Envelope(
            identity = identitySecret,
            devices = devices.map { Dev(it.recipientHandle, it.name, it.pinnedAtEpochMs) },
        )
        val plain = json.encodeToString(envelopeSerializer, envelope).toByteArray(Charsets.UTF_8)
        Age.encryptStream(ByteArrayInputStream(plain), listOf(ScryptRecipient(passphrase)), out)
    }

    /** Decrypt an age scrypt file from [input] with [passphrase] and parse the envelope.
     *  Throws [IllegalArgumentException] on a wrong passphrase or a file that isn't a backup. */
    fun import(passphrase: String, input: InputStream): Imported {
        val plain = ByteArrayOutputStream()
        try {
            Age.decryptStream(input, listOf(ScryptIdentity(passphrase)), plain)
        } catch (_: Age.NoMatchingIdentityException) {
            throw IllegalArgumentException("wrong passphrase, or not a RelayPony backup")
        }
        val envelope = try {
            json.decodeFromString(envelopeSerializer, String(plain.toByteArray(), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("backup is unreadable (${e.message ?: "bad format"})")
        }
        return Imported(
            envelope.identity,
            envelope.devices.map { PinnedDevice(it.handle, it.name, it.pinnedAtEpochMs) },
        )
    }
}
