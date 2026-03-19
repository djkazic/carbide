package com.carbide.wallet.data.lnd

import android.util.Log
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lnrpc.LightningOuterClass as LN
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incoming `lnad.create_invoice` requests from the Lightning Address daemon.
 * Listens for custom messages, creates invoices, and sends responses back.
 */
@Singleton
class LnadHandler @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            try {
                val request = LN.SubscribeCustomMessagesRequest.getDefaultInstance()
                lndStream(
                    lndmobile.Lndmobile::subscribeCustomMessages,
                    request,
                    LN.CustomMessage.parser(),
                ).collect { msg ->
                    if (msg.type != 37913) return@collect

                    try {
                        val json = JSONObject(msg.data.toStringUtf8())
                        val method = json.optString("method", "")

                        if (method == "lnad.create_invoice") {
                            handleCreateInvoice(msg.peer, json)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to handle custom message", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Custom message subscription ended", e)
                started = false
            }
        }
    }

    private suspend fun handleCreateInvoice(peerBytes: ByteString, request: JSONObject) {
        val id = request.optString("id", "")
        val params = request.optJSONObject("params") ?: JSONObject()
        val amountMsat = params.optLong("amount_msat", 0)
        val memo = params.optString("memo", "")

        Log.d(TAG, "Invoice request: id=$id amount_msat=$amountMsat memo=$memo")

        try {
            val amountSat = amountMsat / 1000
            val invoiceReq = LN.Invoice.newBuilder()
                .setValue(amountSat)
                .setMemo(memo)
                .setPrivate(true) // include route hints for private channels
                .build()

            val invoiceResp = lndCall(
                lndmobile.Lndmobile::addInvoice,
                invoiceReq,
                LN.AddInvoiceResponse.parser(),
            )

            val response = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("result", JSONObject().apply {
                    put("payment_request", invoiceResp.paymentRequest)
                })
                put("id", id)
            }

            Log.d(TAG, "Sending invoice response: ${invoiceResp.paymentRequest.take(30)}...")

            val sendReq = LN.SendCustomMessageRequest.newBuilder()
                .setPeer(peerBytes)
                .setType(37913)
                .setData(ByteString.copyFromUtf8(response.toString()))
                .build()

            lndCall(
                lndmobile.Lndmobile::sendCustomMessage,
                sendReq,
                LN.SendCustomMessageResponse.parser(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create invoice", e)

            // Send error response
            val response = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("error", JSONObject().apply {
                    put("code", -32603)
                    put("message", e.message ?: "Internal error")
                })
                put("id", id)
            }

            try {
                val sendReq = LN.SendCustomMessageRequest.newBuilder()
                    .setPeer(peerBytes)
                    .setType(37913)
                    .setData(ByteString.copyFromUtf8(response.toString()))
                    .build()

                lndCall(
                    lndmobile.Lndmobile::sendCustomMessage,
                    sendReq,
                    LN.SendCustomMessageResponse.parser(),
                )
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "LnadHandler"
    }
}
