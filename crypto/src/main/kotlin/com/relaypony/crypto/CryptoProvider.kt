package com.relaypony.crypto

import java.io.InputStream
import java.io.OutputStream

/** Opaque handle to a recipient's public key material, specific to a [CryptoProvider]. */
interface Recipient

/** Opaque handle to a local secret identity, specific to a [CryptoProvider]. */
interface Identity

/**
 * Details of a verified signature on a decrypted payload. age carries no signatures, so
 * [AgeProvider] always returns null; a future OpenPGP provider (in PGPony) returns sender
 * details when a valid signature is present.
 */
data class VerifiedSender(
    val keyId: String,
    val verified: Boolean,
)

/**
 * The cipher-agnostic crypto seam (architecture spec section 11).
 *
 * RelayPony's transport moves opaque, scheme-tagged blobs and never inspects them. A
 * CryptoProvider is the only thing that understands a given encryption scheme: it turns
 * plaintext into those blobs and back. v1 ships [AgeProvider] only; PGPony will later register
 * an OpenPGP provider over the very same transport, distinguished by [schemeId].
 *
 * This interface is the load-bearing decision the build order locks before the wire format
 * (Phase 1 before Phase 2): freezing it here is what keeps the later PGPony reuse cheap.
 */
interface CryptoProvider {
    /** One-byte scheme tag written into the HELLO frame (0x01 = age, 0x02 = OpenPGP). */
    val schemeId: Byte

    /** Decode a recipient handle from a scanned QR pairing payload. */
    fun recipientFromQr(payload: ByteArray): Recipient

    /**
     * Encrypt all bytes from [source] to every recipient in [to], writing ciphertext to [sink].
     * [signAs] is ignored by providers without signing support (e.g. age).
     */
    fun encryptStream(
        to: List<Recipient>,
        source: InputStream,
        sink: OutputStream,
        signAs: Identity? = null,
    )

    /**
     * Decrypt all bytes from [source] using [identity], writing plaintext to [sink].
     * Returns sender details if the payload carried a verifiable signature, otherwise null.
     */
    fun decryptStream(
        identity: Identity,
        source: InputStream,
        sink: OutputStream,
    ): VerifiedSender?
}
