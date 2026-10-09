package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** BouncyCastle-backed primitives for the CarPlay pairing and control channel. */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private const val LABEL_SIZE = 8
    private val random = SecureRandom()

    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        source: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(length))
        val processed = cipher.processBytes(source, offset, length, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    /** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray =
        nonce64(counter, ByteArray(NONCE_SIZE))

    fun nonce64(counter: Long, target: ByteArray): ByteArray {
        require(target.size >= NONCE_SIZE) { "nonce target must be at least 12 bytes" }
        for (index in 0 until 4) target[index] = 0
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            target[index] = value.toByte()
            value = value ushr 8
        }
        return target
    }

    /** 12-byte nonce from an eight-byte ASCII label placed after four zero bytes. */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.asciiBytes()
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))
        return nonce
    }

    private fun digest(digest: Digest, parts: Array<out ByteArray>): ByteArray {
        for (part in parts) digest.update(part, 0, part.size)
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }
}


/**
 * Reusable ChaCha20-Poly1305 opener for one media stream.
 *
 * Each ScreenStream/AudioStream owns its instance and calls it from one receive thread.
 * Reinitializing the cipher for every nonce preserves AEAD semantics while avoiding a
 * ChaCha20Poly1305 and KeyParameter allocation per frame/packet on legacy Android.
 */
internal class AirPlayChaChaOpener(key: ByteArray) {
    private val cipher = ChaCha20Poly1305()
    private val keyParameter = KeyParameter(key)

    fun openWithPrefix(
        nonce: ByteArray,
        source: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray,
        prefixSource: ByteArray,
        prefixLength: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        require(prefixLength >= 0 && prefixLength <= prefixSource.size)
        cipher.init(false, AEADParameters(keyParameter, MAC_BITS, nonce, aad))
        val plainCapacity = cipher.getOutputSize(length)
        val output = ByteArray(prefixLength + plainCapacity)
        if (prefixLength > 0) prefixSource.copyInto(output, 0, 0, prefixLength)
        val processed = cipher.processBytes(source, offset, length, output, prefixLength)
        val finalized = cipher.doFinal(output, prefixLength + processed)
        val outputLength = prefixLength + processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    fun open(
        nonce: ByteArray,
        source: ByteArray,
        offset: Int = 0,
        length: Int = source.size - offset,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        cipher.init(false, AEADParameters(keyParameter, MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(length))
        val processed = cipher.processBytes(source, offset, length, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    private companion object {
        const val MAC_BITS = 128
    }
}


/** Reusable one-thread ChaCha20-Poly1305 sealer for high-frequency control/event frames. */
internal class AirPlayChaChaSealer(key: ByteArray) {
    private val cipher = ChaCha20Poly1305()
    private val keyParameter = KeyParameter(key)

    fun sealInto(
        nonce: ByteArray,
        aad: ByteArray,
        first: ByteArray,
        second: ByteArray,
        target: ByteArray,
        targetOffset: Int,
    ): Int {
        require(targetOffset >= 0 && targetOffset <= target.size)
        cipher.init(true, AEADParameters(keyParameter, MAC_BITS, nonce, aad))
        val required = cipher.getOutputSize(first.size + second.size)
        require(target.size - targetOffset >= required) { "target is too small for sealed output" }
        var written = cipher.processBytes(first, 0, first.size, target, targetOffset)
        written += cipher.processBytes(
            second,
            0,
            second.size,
            target,
            targetOffset + written,
        )
        written += cipher.doFinal(target, targetOffset + written)
        return written
    }

    private companion object {
        const val MAC_BITS = 128
    }
}
