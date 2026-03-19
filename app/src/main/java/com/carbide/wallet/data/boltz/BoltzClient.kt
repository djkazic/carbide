package com.carbide.wallet.data.boltz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoltzClient @Inject constructor() {

    private val baseUrl = "https://boltz-api.eldamar.icu"

    /** Get reverse swap pair info (limits, fees). */
    suspend fun getReverseSwapInfo(): ReverseSwapInfo = withContext(Dispatchers.IO) {
        val json = JSONObject(URL("$baseUrl/v2/swap/reverse").readText())
        val btc = json.getJSONObject("BTC").getJSONObject("BTC")
        val limits = btc.getJSONObject("limits")
        val fees = btc.getJSONObject("fees")
        ReverseSwapInfo(
            minAmount = limits.getLong("minimal"),
            maxAmount = limits.getLong("maximal"),
            feePercentage = fees.getDouble("percentage"),
            minerFeeSat = fees.getJSONObject("minerFees").getLong("claim") +
                fees.getJSONObject("minerFees").getLong("lockup"),
        )
    }

    /** Create a reverse swap (Lightning → On-chain). */
    suspend fun createReverseSwap(
        invoiceAmountSat: Long,
        claimPubkey: String,
        preimageHash: String,
        onchainAddress: String,
    ): ReverseSwapResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("invoiceAmount", invoiceAmountSat)
            put("from", "BTC")
            put("to", "BTC")
            put("claimPublicKey", claimPubkey)
            put("preimageHash", preimageHash)
            put("address", onchainAddress)
        }

        val conn = URL("$baseUrl/v2/swap/reverse").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        android.util.Log.d("BoltzClient", "POST /v2/swap/reverse body=${body}")
        conn.outputStream.write(body.toString().toByteArray())

        android.util.Log.d("BoltzClient", "Response code: ${conn.responseCode}")
        val responseText = if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            android.util.Log.e("BoltzClient", "Boltz error ${conn.responseCode}: $err")
            throw RuntimeException("Boltz error ${conn.responseCode}: $err")
        }
        android.util.Log.d("BoltzClient", "Response: ${responseText.take(200)}")

        val json = JSONObject(responseText)
        ReverseSwapResponse(
            id = json.getString("id"),
            invoice = json.getString("invoice"),
            lockupAddress = json.getString("lockupAddress"),
            refundPubkey = json.getString("refundPublicKey"),
            timeoutBlockHeight = json.getInt("timeoutBlockHeight"),
            onchainAmount = json.getLong("onchainAmount"),
            blindingKey = json.optString("blindingKey", ""),
            swapTree = json.optJSONObject("swapTree")?.toString() ?: "",
        )
    }

    /** Get submarine swap pair info. */
    suspend fun getSubmarineSwapInfo(): SubmarineSwapInfo = withContext(Dispatchers.IO) {
        val json = JSONObject(URL("$baseUrl/v2/swap/submarine").readText())
        val btc = json.getJSONObject("BTC").getJSONObject("BTC")
        val limits = btc.getJSONObject("limits")
        val fees = btc.getJSONObject("fees")
        SubmarineSwapInfo(
            minAmount = limits.getLong("minimal"),
            maxAmount = limits.getLong("maximal"),
            feePercentage = fees.getDouble("percentage"),
            minerFeeSat = fees.getLong("minerFees"),
        )
    }

    /** Create a submarine swap (On-chain → Lightning). */
    suspend fun createSubmarineSwap(
        invoice: String,
        refundPubkey: String,
    ): SubmarineSwapResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("from", "BTC")
            put("to", "BTC")
            put("invoice", invoice)
            put("refundPublicKey", refundPubkey)
        }

        val conn = URL("$baseUrl/v2/swap/submarine").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())

        val responseText = if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw RuntimeException("Boltz error ${conn.responseCode}: $err")
        }

        val json = JSONObject(responseText)
        SubmarineSwapResponse(
            id = json.getString("id"),
            address = json.getString("address"),
            expectedAmount = json.getLong("expectedAmount"),
            bip21 = json.optString("bip21", ""),
            claimPubkey = json.getString("claimPublicKey"),
            swapTree = json.optJSONObject("swapTree")?.toString() ?: "",
            timeoutBlockHeight = json.getInt("timeoutBlockHeight"),
        )
    }

    /** Get submarine swap claim details (Boltz's nonce + tx hash for cooperative signing). */
    suspend fun getSubmarineClaimDetails(id: String): SubmarineClaimDetails = withContext(Dispatchers.IO) {
        val json = JSONObject(URL("$baseUrl/v2/swap/submarine/$id/claim").readText())
        if (json.has("error")) throw RuntimeException(json.getString("error"))
        SubmarineClaimDetails(
            preimage = json.getString("preimage"),
            pubNonce = json.getString("pubNonce"),
            transactionHash = json.getString("transactionHash"),
            publicKey = json.getString("publicKey"),
        )
    }

    /** Post cooperative claim signature for submarine swap. */
    suspend fun postSubmarineClaimSignature(
        id: String,
        pubNonce: String,
        partialSignature: String,
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("pubNonce", pubNonce)
            put("partialSignature", partialSignature)
        }

        val conn = URL("$baseUrl/v2/swap/submarine/$id/claim").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())

        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw RuntimeException("Boltz claim sig error ${conn.responseCode}: $err")
        }
    }

    /** Post cooperative refund for submarine swap. Boltz returns their partial sig. */
    suspend fun postSubmarineRefund(
        id: String,
        pubNonce: String,
        transaction: String,
        index: Int,
    ): BoltzPartialSig = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("pubNonce", pubNonce)
            put("transaction", transaction)
            put("index", index)
        }

        val conn = URL("$baseUrl/v2/swap/submarine/$id/refund").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())

        val responseText = if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw RuntimeException("Boltz refund error ${conn.responseCode}: $err")
        }

        val json = JSONObject(responseText)
        BoltzPartialSig(
            pubNonce = json.getString("pubNonce"),
            partialSignature = json.getString("partialSignature"),
        )
    }

    /** Get the lockup transaction for a submarine swap. */
    suspend fun getSubmarineTransaction(id: String): LockupTransaction = withContext(Dispatchers.IO) {
        val json = JSONObject(URL("$baseUrl/v2/swap/submarine/$id/transaction").readText())
        LockupTransaction(
            id = json.getString("id"),
            hex = json.getString("hex"),
            timeoutBlockHeight = json.getInt("timeoutBlockHeight"),
        )
    }

    /** Get swap status. */
    suspend fun getSwapStatus(id: String): String = withContext(Dispatchers.IO) {
        val json = JSONObject(URL("$baseUrl/v2/swap/$id").readText())
        json.getString("status")
    }

    /** Get the lockup transaction for a reverse swap. */
    suspend fun getReverseTransaction(id: String): LockupTransaction = withContext(Dispatchers.IO) {
        val json = JSONObject(URL("$baseUrl/v2/swap/reverse/$id/transaction").readText())
        LockupTransaction(
            id = json.getString("id"),
            hex = json.getString("hex"),
            timeoutBlockHeight = json.getInt("timeoutBlockHeight"),
        )
    }

    /** Post claim for reverse swap. Send just preimage to settle invoice. */
    suspend fun postReverseClaimDetails(
        id: String,
        preimage: String,
        pubNonce: String,
        partialSignature: String,
        transactionHex: String,
    ): ClaimResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("preimage", preimage)
            // Only include these if they're set — Boltz requires all-or-none
            if (pubNonce.isNotEmpty() && transactionHex.isNotEmpty()) {
                put("pubNonce", pubNonce)
                put("partialSignature", partialSignature)
                put("transaction", transactionHex)
                put("index", 0)
            }
        }

        val conn = URL("$baseUrl/v2/swap/reverse/$id/claim").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())

        val responseText = if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw RuntimeException("Boltz claim error ${conn.responseCode}: $err")
        }

        val json = JSONObject(responseText)
        ClaimResponse(txid = json.optString("transactionId", ""))
    }

    /** Get Boltz's claim details (partial sig + nonce) for reverse swap. */
    suspend fun getReverseClaimDetails(id: String, preimage: String, pubNonce: String, transactionHex: String): BoltzPartialSig =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("preimage", preimage)
                put("pubNonce", pubNonce)
                put("transaction", transactionHex)
                put("index", 0)
            }

            val conn = URL("$baseUrl/v2/swap/reverse/$id/claim").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(body.toString().toByteArray())

            val responseText = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw RuntimeException("Boltz claim error ${conn.responseCode}: $err")
            }

            val json = JSONObject(responseText)
            BoltzPartialSig(
                pubNonce = json.getString("pubNonce"),
                partialSignature = json.getString("partialSignature"),
            )
        }
}

data class ReverseSwapInfo(
    val minAmount: Long,
    val maxAmount: Long,
    val feePercentage: Double,
    val minerFeeSat: Long,
) {
    fun totalFeeSat(amountSat: Long): Long =
        (amountSat * feePercentage / 100).toLong() + minerFeeSat

    fun onchainAmountSat(invoiceAmountSat: Long): Long =
        invoiceAmountSat - totalFeeSat(invoiceAmountSat)
}

data class ReverseSwapResponse(
    val id: String,
    val invoice: String,
    val lockupAddress: String,
    val refundPubkey: String,
    val timeoutBlockHeight: Int,
    val onchainAmount: Long,
    val blindingKey: String,
    val swapTree: String,
)

data class ClaimResponse(
    val txid: String,
)

data class SubmarineSwapInfo(
    val minAmount: Long,
    val maxAmount: Long,
    val feePercentage: Double,
    val minerFeeSat: Long,
) {
    fun totalFeeSat(amountSat: Long): Long =
        (amountSat * feePercentage / 100).toLong() + minerFeeSat

    fun expectedAmountSat(invoiceAmountSat: Long): Long =
        invoiceAmountSat + totalFeeSat(invoiceAmountSat)
}

data class SubmarineSwapResponse(
    val id: String,
    val address: String,
    val expectedAmount: Long,
    val bip21: String,
    val claimPubkey: String,
    val swapTree: String,
    val timeoutBlockHeight: Int,
)

data class SubmarineClaimDetails(
    val preimage: String,
    val pubNonce: String,
    val transactionHash: String,
    val publicKey: String,
)

data class LockupTransaction(
    val id: String,
    val hex: String,
    val timeoutBlockHeight: Int,
)

data class BoltzPartialSig(
    val pubNonce: String,
    val partialSignature: String,
)
