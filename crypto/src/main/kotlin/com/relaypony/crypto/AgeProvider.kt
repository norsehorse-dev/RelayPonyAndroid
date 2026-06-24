package com.relaypony.crypto

import com.agepony.core.Age
import com.agepony.core.recipients.X25519Identity
import com.agepony.core.recipients.X25519Recipient
import java.io.InputStream
import java.io.OutputStream

/**
 * age implementation of [CryptoProvider] (scheme 0x01), wrapping AgePony's streaming API.
 *
 * Trust model: channel trust. A recipient is an age X25519 public key (`age1...`), normally
 * obtained out-of-band via the QR pairing scan. age carries no signatures, so [signAs] is
 * ignored and [decryptStream] always returns null.
 *
 * The recipient/identity handles below are AgeProvider-specific implementations of the opaque
 * [Recipient] / [Identity] seam types; the provider downcasts to them and rejects foreign types,
 * which is how the cipher-agnostic transport stays unaware of any particular scheme.
 */
class AgeProvider : CryptoProvider {

    override val schemeId: Byte = SCHEME_AGE

    /**
     * Decode a recipient from a scanned QR payload. In Phase 1 the payload is simply the UTF-8
     * `age1...` recipient string. The richer QR envelope (scheme byte + connection info + version)
     * is layered on in Phase 4 pairing; this method will then decode the recipient field of it.
     */
    override fun recipientFromQr(payload: ByteArray): Recipient =
        AgeRecipientHandle(X25519Recipient(payload.toString(Charsets.UTF_8).trim()))

    override fun encryptStream(
        to: List<Recipient>,
        source: InputStream,
        sink: OutputStream,
        signAs: Identity?,
    ) {
        require(to.isNotEmpty()) { "must have at least one recipient" }
        val recipients = to.map { it.asAge().recipient }
        // List<X25519Recipient> satisfies List<AgeRecipient> by covariance.
        Age.encryptStream(source, recipients, sink)
    }

    override fun decryptStream(
        identity: Identity,
        source: InputStream,
        sink: OutputStream,
    ): VerifiedSender? {
        Age.decryptStream(source, listOf(identity.asAge().identity), sink)
        return null   // age has no signatures
    }

    // --- Helpers for producing identities/recipients (used by pairing in Phase 4 and by tests) ---

    /** Create a fresh local identity. */
    fun generateIdentity(): Identity = AgeIdentityHandle(X25519Identity.generate())

    /** Load an identity from its `AGE-SECRET-KEY-1...` string. */
    fun identityFromString(secret: String): Identity =
        AgeIdentityHandle(X25519Identity(secret.trim()))

    /** Parse a recipient from its `age1...` string. */
    fun recipientFromString(age1: String): Recipient =
        AgeRecipientHandle(X25519Recipient(age1.trim()))

    /** The public recipient corresponding to a local identity (what a device advertises in its QR). */
    fun recipientOf(identity: Identity): Recipient =
        AgeRecipientHandle(X25519Recipient(identity.asAge().publicKey))

    /** Encode a recipient as the QR payload bytes (inverse of [recipientFromQr] in Phase 1). */
    fun recipientToQr(recipient: Recipient): ByteArray =
        recipient.asAge().recipient.toBech32().toByteArray(Charsets.UTF_8)

    /** Serialize a local identity to its AGE-SECRET-KEY string, for secure persistence. */
    fun identityToString(identity: Identity): String =
        identity.asAge().identity.toBech32()

    private fun Recipient.asAge(): AgeRecipientHandle =
        this as? AgeRecipientHandle
            ?: throw IllegalArgumentException("AgeProvider requires age recipients; got ${this::class.java.name}")

    private fun Identity.asAge(): AgeIdentityHandle =
        this as? AgeIdentityHandle
            ?: throw IllegalArgumentException("AgeProvider requires age identities; got ${this::class.java.name}")

    companion object {
        const val SCHEME_AGE: Byte = 0x01
    }
}

/** age recipient handle: wraps an AgePony [X25519Recipient]. */
class AgeRecipientHandle internal constructor(internal val recipient: X25519Recipient) : Recipient

/** age identity handle: wraps an AgePony [X25519Identity]. */
class AgeIdentityHandle internal constructor(internal val identity: X25519Identity) : Identity {
    internal val publicKey: ByteArray get() = identity.publicKey
}
