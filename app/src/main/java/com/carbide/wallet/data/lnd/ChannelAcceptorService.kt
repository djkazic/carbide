package com.carbide.wallet.data.lnd

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lnrpc.LightningOuterClass as LN
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a ChannelAcceptor that auto-accepts incoming channels
 * with a custom (lower) reserve requirement.
 */
@Singleton
class ChannelAcceptorService @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            try {
                Log.d(TAG, "Starting channel acceptor (reserve=$RESERVE_SAT sat)")

                val sendStream = lndmobile.Lndmobile.channelAcceptor(object : lndmobile.RecvStream {
                    override fun onResponse(p0: ByteArray?) {
                        val bytes = p0 ?: return
                        try {
                            val request = LN.ChannelAcceptRequest.parseFrom(bytes)
                            val peerHex = request.nodePubkey.toByteArray()
                                .joinToString("") { "%02x".format(it) }

                            Log.d(TAG, "Channel open request from ${peerHex.take(16)} " +
                                "amt=${request.fundingAmt} reserve=${request.channelReserve}")

                            val response = LN.ChannelAcceptResponse.newBuilder()
                                .setAccept(true)
                                .setPendingChanId(request.pendingChanId)
                                .setReserveSat(RESERVE_SAT)
                                .build()

                            sendStream?.send(response.toByteArray())
                            Log.d(TAG, "Accepted channel with reserve=$RESERVE_SAT sat")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to handle channel accept: ${e.message}")
                        }
                    }

                    override fun onError(p0: java.lang.Exception?) {
                        Log.e(TAG, "Channel acceptor error: ${p0?.message}")
                        started = false
                    }
                })

                // Keep reference for sending responses
                this@ChannelAcceptorService.sendStream = sendStream

            } catch (e: Exception) {
                Log.e(TAG, "Channel acceptor failed to start: ${e.message}")
                started = false
            }
        }
    }

    private var sendStream: lndmobile.SendStream? = null

    companion object {
        private const val TAG = "ChanAcceptor"
        private const val RESERVE_SAT = 1000L
    }
}
