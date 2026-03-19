package com.carbide.wallet.data.lnd

import android.util.Log
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lnrpc.LightningOuterClass as LN
import signrpc.SignerOuterClass as Signer
import java.math.BigInteger
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LNURL-Auth (LUD-04) implementation.
 *
 * Uses LND's signMessage to derive a deterministic hashing key,
 * then HMAC-SHA256 to derive domain-specific linking keys,
 * and Android's java.security for the actual ECDSA signing (since
 * LND's SignMessage always SHA256-hashes the input, which we don't want).
 */
@Singleton
class LnurlAuth @Inject constructor() {

    private var hashingKey: ByteArray? = null

    fun isLnurlAuth(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("tag=login")) return true
        if (lower.startsWith("keyauth://")) return true
        if (lower.startsWith("lnurl1")) {
            return try { parse(url); true } catch (_: Exception) { false }
        }
        return false
    }

    fun parse(url: String): LnurlAuthRequest {
        val decoded = if (url.lowercase().startsWith("lnurl1")) decodeLnurl(url) else url
        val uri = java.net.URI(decoded)
        val params = uri.query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

        val k1 = params["k1"] ?: throw IllegalArgumentException("Missing k1")
        if (params["tag"] != "login") throw IllegalArgumentException("Not a login request")

        return LnurlAuthRequest(
            callback = decoded,
            k1 = k1,
            domain = uri.host,
            action = params["action"] ?: "login",
        )
    }

    suspend fun authenticate(request: LnurlAuthRequest): LnurlAuthResult {
        // 1. Get or derive the hashing key from LND's node key
        val hKey = getHashingKey()

        // 2. Derive domain-specific linking private key
        val linkingPrivKey = hmacSha256(hKey, request.domain.toByteArray())
        Log.d(TAG, "LNURL-Auth: domain=${request.domain}")

        // 3. Derive the public key from the private key
        val pubKey = deriveCompressedPubKey(linkingPrivKey)
        val pubKeyHex = pubKey.toHex()
        Log.d(TAG, "LNURL-Auth: linking pubkey=$pubKeyHex")

        // 4. Sign k1 (raw 32 bytes) with ECDSA — no additional hashing
        val k1Bytes = request.k1.hexToBytes()
        val derSig = ecdsaSignRaw(linkingPrivKey, k1Bytes)
        val sigHex = derSig.toHex()
        Log.d(TAG, "LNURL-Auth: DER sig=$sigHex")

        // 5. Hit the callback
        val separator = if (request.callback.contains("?")) "&" else "?"
        val authUrl = "${request.callback}${separator}sig=$sigHex&key=$pubKeyHex"
        Log.d(TAG, "LNURL-Auth: callback=${authUrl.take(100)}...")

        return withContext(Dispatchers.IO) {
            val response = URL(authUrl).readText()
            val json = org.json.JSONObject(response)
            val status = json.optString("status", "")

            if (status.uppercase() == "OK") {
                Log.d(TAG, "LNURL-Auth: success!")
                LnurlAuthResult(success = true, message = "Authenticated with ${request.domain}")
            } else {
                val reason = json.optString("reason", "Unknown error")
                Log.e(TAG, "LNURL-Auth: failed: $reason")
                LnurlAuthResult(success = false, message = reason)
            }
        }
    }

    /**
     * Get the hashing key by signing a canonical message with LND's node key.
     * This is deterministic — same node always produces the same hashing key.
     */
    private suspend fun getHashingKey(): ByteArray {
        hashingKey?.let { return it }

        // Sign a canonical phrase with the node's identity key
        val signResp = lndCall(
            lndmobile.Lndmobile::signMessage,
            LN.SignMessageRequest.newBuilder()
                .setMsg(ByteString.copyFromUtf8("lnurlauth"))
                .build(),
            LN.SignMessageResponse.parser(),
        )

        // The signature is deterministic for the same message + key
        // Use it as seed material for the hashing key
        val sigBytes = signResp.signature.toByteArray()
        val hKey = sha256(sigBytes)
        hashingKey = hKey
        Log.d(TAG, "LNURL-Auth: derived hashing key")
        return hKey
    }

    /**
     * Sign a 32-byte digest directly with ECDSA (no hashing).
     * Uses Android's java.security with secp256k1.
     */
    private fun ecdsaSignRaw(privKeyBytes: ByteArray, digest: ByteArray): ByteArray {
        return secp256k1Sign(privKeyBytes, digest)
    }

    /**
     * Pure secp256k1 ECDSA signing using BigInteger.
     * Signs a 32-byte message digest directly (no hashing).
     */
    private fun secp256k1Sign(privKeyBytes: ByteArray, digest: ByteArray): ByteArray {
        val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
        val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
        val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)

        val privKey = BigInteger(1, privKeyBytes).mod(n)
        val z = BigInteger(1, digest)

        // Deterministic k (RFC 6979)
        val k = generateDeterministicK(privKeyBytes, digest, n)

        // R = k * G
        val R = ecMultiply(Gx, Gy, k, p)
        val r = R.first.mod(n)
        if (r == BigInteger.ZERO) throw RuntimeException("Invalid r")

        // s = k^-1 * (z + r * privKey) mod n
        var s = k.modInverse(n).multiply(z.add(r.multiply(privKey))).mod(n)

        // Enforce low-S (BIP-62)
        val halfN = n.shiftRight(1)
        if (s > halfN) s = n.subtract(s)

        // DER encode
        return derEncode(
            r.toByteArray().let { if (it[0] == 0.toByte() && it.size > 32) it.copyOfRange(1, it.size) else it },
            s.toByteArray().let { if (it[0] == 0.toByte() && it.size > 32) it.copyOfRange(1, it.size) else it },
        )
    }

    /** RFC 6979 deterministic k generation */
    private fun generateDeterministicK(privKey: ByteArray, hash: ByteArray, n: BigInteger): BigInteger {
        var v = ByteArray(32) { 0x01 }
        var kk = ByteArray(32) { 0x00 }

        kk = hmacSha256(kk, v + byteArrayOf(0x00) + privKey + hash)
        v = hmacSha256(kk, v)
        kk = hmacSha256(kk, v + byteArrayOf(0x01) + privKey + hash)
        v = hmacSha256(kk, v)

        while (true) {
            v = hmacSha256(kk, v)
            val candidate = BigInteger(1, v)
            if (candidate >= BigInteger.ONE && candidate < n) return candidate
            kk = hmacSha256(kk, v + byteArrayOf(0x00))
            v = hmacSha256(kk, v)
        }
    }

    /** Elliptic curve point multiplication using double-and-add */
    private fun ecMultiply(Gx: BigInteger, Gy: BigInteger, k: BigInteger, p: BigInteger): Pair<BigInteger, BigInteger> {
        var rx = Gx; var ry = Gy
        var qx: BigInteger? = null; var qy: BigInteger? = null

        val bits = k.bitLength()
        for (i in 0 until bits) {
            if (k.testBit(i)) {
                if (qx == null) {
                    qx = rx; qy = ry
                } else {
                    val r = ecAdd(qx, qy!!, rx, ry, p)
                    qx = r.first; qy = r.second
                }
            }
            val doubled = ecDouble(rx, ry, p)
            rx = doubled.first; ry = doubled.second
        }
        return Pair(qx!!, qy!!)
    }

    private fun ecAdd(x1: BigInteger, y1: BigInteger, x2: BigInteger, y2: BigInteger, p: BigInteger): Pair<BigInteger, BigInteger> {
        if (x1 == x2 && y1 == y2) return ecDouble(x1, y1, p)
        val lam = (y2 - y1).multiply((x2 - x1).modInverse(p)).mod(p)
        val x3 = (lam.multiply(lam) - x1 - x2).mod(p)
        val y3 = (lam.multiply(x1 - x3) - y1).mod(p)
        return Pair(x3, y3)
    }

    private fun ecDouble(x: BigInteger, y: BigInteger, p: BigInteger): Pair<BigInteger, BigInteger> {
        val lam = (BigInteger.valueOf(3).multiply(x).multiply(x))
            .multiply((BigInteger.TWO.multiply(y)).modInverse(p)).mod(p)
        val x3 = (lam.multiply(lam) - BigInteger.TWO.multiply(x)).mod(p)
        val y3 = (lam.multiply(x - x3) - y).mod(p)
        return Pair(x3, y3)
    }

    /** Derive compressed public key from private key */
    private fun deriveCompressedPubKey(privKeyBytes: ByteArray): ByteArray {
        val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
        val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
        val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)

        val privKey = BigInteger(1, privKeyBytes).mod(n)
        val point = ecMultiply(Gx, Gy, privKey, p)

        val prefix = if (point.second.testBit(0)) 0x03.toByte() else 0x02.toByte()
        val xBytes = point.first.toByteArray()
        val padded = when {
            xBytes.size == 33 && xBytes[0] == 0.toByte() -> xBytes.copyOfRange(1, 33)
            xBytes.size < 32 -> ByteArray(32 - xBytes.size) + xBytes
            else -> xBytes
        }
        return byteArrayOf(prefix) + padded
    }

    private fun derEncode(r: ByteArray, s: ByteArray): ByteArray {
        fun encInt(bytes: ByteArray): ByteArray {
            val padded = if (bytes[0].toInt() and 0x80 != 0) byteArrayOf(0x00) + bytes else bytes
            return byteArrayOf(0x02, padded.size.toByte()) + padded
        }
        val rEnc = encInt(r)
        val sEnc = encInt(s)
        return byteArrayOf(0x30, (rEnc.size + sEnc.size).toByte()) + rEnc + sEnc
    }

    private fun decodeLnurl(lnurl: String): String {
        val hrpEnd = lnurl.lowercase().lastIndexOf('1')
        val dataStr = lnurl.lowercase().substring(hrpEnd + 1)
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val data = dataStr.map { charset.indexOf(it) }
        val values = data.dropLast(6)
        var acc = 0; var bits = 0
        val result = mutableListOf<Byte>()
        for (v in values) {
            acc = (acc shl 5) or v; bits += 5
            while (bits >= 8) { bits -= 8; result.add(((acc shr bits) and 0xff).toByte()) }
        }
        return String(result.toByteArray())
    }

    companion object {
        private const val TAG = "LnurlAuth"

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.hexToBytes(): ByteArray {
            val data = ByteArray(length / 2)
            for (i in data.indices) {
                data[i] = ((Character.digit(this[i * 2], 16) shl 4) +
                    Character.digit(this[i * 2 + 1], 16)).toByte()
            }
            return data
        }
    }
}

data class LnurlAuthRequest(
    val callback: String,
    val k1: String,
    val domain: String,
    val action: String,
)

data class LnurlAuthResult(
    val success: Boolean,
    val message: String,
)
