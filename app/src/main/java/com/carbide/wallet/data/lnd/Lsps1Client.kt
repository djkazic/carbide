package com.carbide.wallet.data.lnd

import android.util.Log
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import lnrpc.LightningOuterClass as LN
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LSPS1 client implementing the channel purchase protocol over
 * Lightning custom messages (LSPS0 transport, message type 37913).
 */
@Singleton
class Lsps1Client @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var subscribed = false

    /** Start listening for custom message responses from the LSP. */
    fun startListening() {
        if (subscribed) return
        subscribed = true

        val request = LN.SubscribeCustomMessagesRequest.getDefaultInstance()
        scope.launch {
            try {
                lndStream(
                    lndmobile.Lndmobile::subscribeCustomMessages,
                    request,
                    LN.CustomMessage.parser(),
                ).collect { msg ->
                    Log.d(TAG, "Custom message type=${msg.type}")
                    if (msg.type != LSPS0_MSG_TYPE) return@collect
                    try {
                        val raw = msg.data.toStringUtf8()
                        Log.d(TAG, "LSPS0 response: $raw")
                        val json = JSONObject(raw)
                        val id = json.optString("id", "")
                        if (id.isNotEmpty()) {
                            val deferred = pendingRequests.remove(id)
                            if (deferred != null) {
                                Log.d(TAG, "Matched request id=$id")
                                deferred.complete(json)
                            } else {
                                Log.w(TAG, "No pending request for id=$id")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse custom message", e)
                    }
                }
            } catch (_: Exception) {
                subscribed = false
            }
        }
    }

    /** Send a JSON-RPC request to the LSP peer and await the response. */
    private suspend fun rpcCall(
        peerPubkey: String,
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000,
    ): JSONObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        Log.d(TAG, "rpcCall method=$method id=$id")
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
            put("id", id)
        }

        val peerBytes = ByteString.copyFrom(hexToBytes(peerPubkey))
        val dataBytes = ByteString.copyFromUtf8(payload.toString())

        val request = LN.SendCustomMessageRequest.newBuilder()
            .setPeer(peerBytes)
            .setType(LSPS0_MSG_TYPE)
            .setData(dataBytes)
            .build()

        lndCall(
            lndmobile.Lndmobile::sendCustomMessage,
            request,
            LN.SendCustomMessageResponse.parser(),
        )

        return withTimeout(timeoutMs) { deferred.await() }
    }

    /** Get LSP capabilities and limits. */
    suspend fun getInfo(peerPubkey: String): Lsps1Info {
        val response = rpcCall(peerPubkey, "lsps1.get_info")

        if (response.has("error")) {
            val err = response.getJSONObject("error")
            throw Lsps1Error(err.optInt("code"), err.optString("message"))
        }

        val result = response.getJSONObject("result")
        val opts = result.optJSONObject("options") ?: result
        return Lsps1Info(
            minChannelBalanceSat = opts.optLong("min_initial_lsp_balance_sat", 0),
            maxChannelBalanceSat = opts.optLong("max_initial_lsp_balance_sat", 0),
            minClientBalanceSat = opts.optLong("min_initial_client_balance_sat", 0),
            maxClientBalanceSat = opts.optLong("max_initial_client_balance_sat", 0),
            minChannelConfirmations = opts.optInt("min_required_channel_confirmations", 0),
            maxChannelExpiryBlocks = opts.optInt("max_channel_expiry_blocks", 0),
            supportsZeroReserve = opts.optBoolean("supports_zero_channel_reserve", false),
        )
    }

    /** Create a channel purchase order. Returns payment details. */
    suspend fun createOrder(
        peerPubkey: String,
        lspBalanceSat: Long,
        clientBalanceSat: Long,
        channelExpiryBlocks: Int,
        refundAddress: String,
        announceChannel: Boolean = false,
    ): Lsps1Order {
        val params = JSONObject().apply {
            put("lsp_balance_sat", lspBalanceSat.toString())
            put("client_balance_sat", clientBalanceSat.toString())
            put("required_channel_confirmations", 1)
            put("funding_confirms_within_blocks", 6)
            put("channel_expiry_blocks", channelExpiryBlocks)
            put("token", "")
            put("refund_onchain_address", refundAddress)
            put("announce_channel", announceChannel)
        }

        val response = rpcCall(peerPubkey, "lsps1.create_order", params, 60_000)

        if (response.has("error")) {
            val err = response.getJSONObject("error")
            val dataMsg = err.optJSONObject("data")?.optString("message") ?: ""
            val msg = err.optString("message", "Unknown error")
            throw Lsps1Error(err.optInt("code"), if (dataMsg.isNotEmpty()) dataMsg else msg)
        }

        val result = response.getJSONObject("result")
        return parseOrder(result)
    }

    /** Poll order status. */
    suspend fun getOrder(peerPubkey: String, orderId: String): Lsps1Order {
        val params = JSONObject().apply {
            put("order_id", orderId)
        }
        val response = rpcCall(peerPubkey, "lsps1.get_order", params)

        if (response.has("error")) {
            val err = response.getJSONObject("error")
            throw Lsps1Error(err.optInt("code"), err.optString("message"))
        }

        return parseOrder(response.getJSONObject("result"))
    }

    private fun parseOrder(result: JSONObject): Lsps1Order {
        val payment = result.optJSONObject("payment")
        val channel = result.optJSONObject("channel")

        // The payment object may have nested bolt11/onchain sub-objects (spec),
        // or flat fields like bolt11_invoice, onchain_address (some LSPs).
        val bolt11Nested = payment?.optJSONObject("bolt11")
        val onchainNested = payment?.optJSONObject("onchain")

        val bolt11Invoice = bolt11Nested?.optString("invoice")
            ?: payment?.optString("bolt11_invoice", null)
        val bolt11State = bolt11Nested?.optString("state")
            ?: payment?.optString("state", null)
        val bolt11Fee = bolt11Nested?.satLong("fee_total_sat")
            ?: payment?.satLong("fee_total_sat")
            ?: 0
        val bolt11Total = bolt11Nested?.satLong("order_total_sat")
            ?: payment?.satLong("order_total_sat")
            ?: 0
        val onchainAddr = onchainNested?.optString("address")
            ?: payment?.optString("onchain_address", null)
        val onchainState = onchainNested?.optString("state")
            ?: payment?.optString("onchain_state", null)
        val onchainFee = onchainNested?.satLong("fee_total_sat")
            ?: payment?.satLong("onchain_fee_total_sat")
            ?: 0
        val onchainTotal = onchainNested?.satLong("order_total_sat")
            ?: payment?.satLong("onchain_order_total_sat")
            ?: 0

        return Lsps1Order(
            orderId = result.optString("order_id"),
            orderState = result.optString("order_state"),
            lspBalanceSat = result.satLong("lsp_balance_sat"),
            clientBalanceSat = result.satLong("client_balance_sat"),
            bolt11Invoice = bolt11Invoice,
            bolt11State = bolt11State,
            bolt11FeeSat = bolt11Fee,
            bolt11TotalSat = bolt11Total,
            onchainAddress = onchainAddr,
            onchainState = onchainState,
            onchainFeeSat = onchainFee,
            onchainTotalSat = onchainTotal,
            fundingOutpoint = channel?.optString("funding_outpoint"),
            channelExpiresAt = channel?.optString("expires_at"),
        )
    }

    /** Parse a JSON field that may be a number or a string-encoded number. */
    private fun JSONObject.satLong(key: String): Long {
        return when (val v = opt(key)) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    companion object {
        private const val TAG = "Lsps1Client"
        const val LSPS0_MSG_TYPE = 37913

        private fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            }
            return data
        }
    }
}

data class Lsps1Info(
    val minChannelBalanceSat: Long,
    val maxChannelBalanceSat: Long,
    val minClientBalanceSat: Long,
    val maxClientBalanceSat: Long,
    val minChannelConfirmations: Int,
    val maxChannelExpiryBlocks: Int,
    val supportsZeroReserve: Boolean,
)

data class Lsps1Order(
    val orderId: String,
    val orderState: String,
    val lspBalanceSat: Long,
    val clientBalanceSat: Long,
    val bolt11Invoice: String?,
    val bolt11State: String?,
    val bolt11FeeSat: Long,
    val bolt11TotalSat: Long,
    val onchainAddress: String?,
    val onchainState: String?,
    val onchainFeeSat: Long,
    val onchainTotalSat: Long,
    val fundingOutpoint: String?,
    val channelExpiresAt: String?,
) {
    val isCompleted get() = orderState == "COMPLETED"
    val isFailed get() = orderState == "FAILED"
    val isPaid get() = bolt11State == "PAID" || bolt11State == "HOLD" || onchainState == "PAID"
}

class Lsps1Error(val code: Int, message: String) : Exception("LSPS1 error $code: $message")
