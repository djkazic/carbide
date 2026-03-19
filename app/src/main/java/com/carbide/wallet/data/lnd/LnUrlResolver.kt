package com.carbide.wallet.data.lnd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class LnUrlPayInfo(
    val callback: String,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val description: String,
    val domain: String,
) {
    val minSendableSat: Long get() = minSendableMsat / 1000
    val maxSendableSat: Long get() = maxSendableMsat / 1000
}

@Singleton
class LnUrlResolver @Inject constructor() {

    /** Returns true if the input looks like a Lightning Address or LNURL. */
    fun isLnAddress(input: String): Boolean {
        val s = input.trim()
        return s.contains("@") && s.contains(".") && !s.startsWith("bitcoin:") && !s.startsWith("lnbc")
    }

    /** Resolve a Lightning Address (user@domain) to LNURL-pay info. */
    suspend fun resolve(address: String): LnUrlPayInfo = withContext(Dispatchers.IO) {
        val trimmed = address.trim().lowercase()
        val parts = trimmed.split("@")
        if (parts.size != 2) throw IllegalArgumentException("Invalid Lightning Address")

        val user = parts[0]
        val domain = parts[1]
        val url = "https://$domain/.well-known/lnurlp/$user"

        val json = JSONObject(URL(url).readText())

        if (json.has("status") && json.optString("status") == "ERROR") {
            throw RuntimeException(json.optString("reason", "LNURL error"))
        }

        LnUrlPayInfo(
            callback = json.getString("callback"),
            minSendableMsat = json.getLong("minSendable"),
            maxSendableMsat = json.getLong("maxSendable"),
            description = json.optJSONArray("metadata")?.let { meta ->
                for (i in 0 until meta.length()) {
                    val entry = meta.optJSONArray(i) ?: continue
                    if (entry.optString(0) == "text/plain") return@let entry.optString(1)
                }
                null
            } ?: "",
            domain = domain,
        )
    }

    /** Call the LNURL-pay callback with an amount to get a bolt11 invoice. */
    suspend fun fetchInvoice(callback: String, amountMsat: Long): String = withContext(Dispatchers.IO) {
        val separator = if (callback.contains("?")) "&" else "?"
        val url = "${callback}${separator}amount=$amountMsat"
        val json = JSONObject(URL(url).readText())

        if (json.has("status") && json.optString("status") == "ERROR") {
            throw RuntimeException(json.optString("reason", "Failed to get invoice"))
        }

        json.getString("pr")
    }
}
