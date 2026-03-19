package com.carbide.wallet.data.lnd

import android.util.Log
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lnrpc.LightningOuterClass as LN
import signrpc.SignerOuterClass as Signer
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the SCB backup daemon (scbd).
 * Auto-uploads channel backups, retrieves them on restore via challenge-response.
 */
@Singleton
class ScbBackupClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseUrl = "https://scb-backup.eldamar.icu"
    private val prefs = context.getSharedPreferences("scb_backup", android.content.Context.MODE_PRIVATE)
    private var nodePubkey: String? = null
    private var started = false
    private var lastUploadedHash: String = prefs.getString("last_hash", "") ?: ""

    /**
     * Start auto-backup: subscribe to channel backup updates and upload each one.
     */
    fun startAutoBackup() {
        if (started) return
        started = true

        scope.launch {
            try {
                // Get our node pubkey
                val info = lndCall(
                    lndmobile.Lndmobile::getInfo,
                    LN.GetInfoRequest.getDefaultInstance(),
                    LN.GetInfoResponse.parser(),
                )
                nodePubkey = info.identityPubkey
                Log.d(TAG, "Auto-backup started for node ${info.identityPubkey.take(16)}")

                // Do an initial backup on startup using the local channel.backup file
                try {
                    val backupFile = java.io.File(context.filesDir, "lnd/channel.backup")
                    if (backupFile.exists()) {
                        val scbBytes = backupFile.readBytes()
                        val hash = MessageDigest.getInstance("SHA-256")
                            .digest(scbBytes).toHex()
                        if (hash != lastUploadedHash) {
                            uploadBackup(scbBytes)
                            lastUploadedHash = hash
                            prefs.edit().putString("last_hash", hash).apply()
                        } else {
                            Log.d(TAG, "Backup unchanged, skipping upload")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Initial backup failed: ${e.message}")
                }

                // Subscribe to channel backup updates
                val request = LN.ChannelBackupSubscription.getDefaultInstance()
                lndStream(
                    lndmobile.Lndmobile::subscribeChannelBackups,
                    request,
                    LN.ChanBackupSnapshot.parser(),
                ).collect { snapshot ->
                    val scbBytes = snapshot.multiChanBackup.multiChanBackup.toByteArray()
                    if (scbBytes.isNotEmpty()) {
                        try {
                            uploadBackup(scbBytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "Backup upload failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-backup subscription ended: ${e.message}")
                started = false
            }
        }
    }

    /**
     * Upload an SCB to the backup server.
     */
    private suspend fun uploadBackup(scbBytes: ByteArray) = withContext(Dispatchers.IO) {
        val pubkey = nodePubkey ?: throw RuntimeException("Node pubkey not available")

        // Sign the SCB with the node key
        val sigResp = lndCall(
            lndmobile.Lndmobile::signMessage,
            LN.SignMessageRequest.newBuilder()
                .setMsg(ByteString.copyFrom(scbBytes))
                .build(),
            LN.SignMessageResponse.parser(),
        )
        // signMessage returns zbase32 — we need to get a DER sig instead
        // Use signerSignMessage with the node's key (family 6, index 0 = node identity)
        val signerResp = lndCall(
            lndmobile.Lndmobile::signerSignMessage,
            Signer.SignMessageReq.newBuilder()
                .setMsg(ByteString.copyFrom(scbBytes))
                .setKeyLoc(Signer.KeyLocator.newBuilder()
                    .setKeyFamily(6) // KeyFamilyNodeKey
                    .setKeyIndex(0)
                    .build())
                .build(),
            Signer.SignMessageResp.parser(),
        )
        val sigHex = signerResp.signature.toByteArray().toHex()

        // Compute proof of work
        Log.d(TAG, "Computing proof of work...")
        val nonce = computePoW(pubkey, 20)
        Log.d(TAG, "PoW found: nonce=$nonce")

        // Upload
        val conn = URL("$baseUrl/backup").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-Pubkey", pubkey)
        conn.setRequestProperty("X-Signature", sigHex)
        conn.setRequestProperty("X-Pow-Nonce", nonce)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.outputStream.write(scbBytes)

        if (conn.responseCode in 200..299) {
            Log.d(TAG, "Backup uploaded (${scbBytes.size} bytes)")
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw RuntimeException("Upload failed ${conn.responseCode}: $err")
        }
    }

    /**
     * Retrieve a backup from the server during wallet restore.
     * Returns the SCB bytes or null if no backup exists.
     */
    suspend fun retrieveBackup(pubkey: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // 1. Get challenge
            val challengeResp = URL("$baseUrl/challenge?pubkey=$pubkey").readText()
            val challengeJson = JSONObject(challengeResp)
            val challenge = challengeJson.getString("challenge")
            Log.d(TAG, "Got challenge: ${challenge.take(16)}...")

            // 2. Sign the challenge
            val challengeBytes = challenge.hexToBytes()
            val sigResp = lndCall(
                lndmobile.Lndmobile::signerSignMessage,
                Signer.SignMessageReq.newBuilder()
                    .setMsg(ByteString.copyFrom(challengeBytes))
                    .setKeyLoc(Signer.KeyLocator.newBuilder()
                        .setKeyFamily(6)
                        .setKeyIndex(0)
                        .build())
                    .build(),
                Signer.SignMessageResp.parser(),
            )
            val sigHex = sigResp.signature.toByteArray().toHex()

            // 3. Post restore request
            val body = JSONObject().apply {
                put("pubkey", pubkey)
                put("challenge", challenge)
                put("signature", sigHex)
            }

            val conn = URL("$baseUrl/restore").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.outputStream.write(body.toString().toByteArray())

            if (conn.responseCode in 200..299) {
                val scbBytes = conn.inputStream.readBytes()
                Log.d(TAG, "Backup retrieved (${scbBytes.size} bytes)")
                scbBytes
            } else {
                Log.d(TAG, "No backup available: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retrieve backup failed: ${e.message}")
            null
        }
    }

    /**
     * Compute proof of work: find nonce where SHA256(pubkey + nonce) has N leading zero bits.
     */
    private fun computePoW(pubkey: String, difficulty: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = pubkey.toByteArray()
        var nonce = 0L

        while (true) {
            val nonceHex = "%016x".format(nonce)
            md.reset()
            md.update(prefix)
            md.update(nonceHex.toByteArray())
            val hash = md.digest()

            if (hasLeadingZeroBits(hash, difficulty)) {
                return nonceHex
            }
            nonce++
        }
    }

    private fun hasLeadingZeroBits(hash: ByteArray, bits: Int): Boolean {
        var remaining = bits
        for (b in hash) {
            if (remaining <= 0) return true
            if (remaining >= 8) {
                if (b != 0.toByte()) return false
                remaining -= 8
            } else {
                val mask = (0xFF shl (8 - remaining)) and 0xFF
                return (b.toInt() and mask) == 0
            }
        }
        return true
    }

    companion object {
        private const val TAG = "ScbBackup"

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
