package com.carbide.wallet.data.lnd

import com.google.protobuf.GeneratedMessageLite
import com.google.protobuf.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps an lnd-mobile unary RPC call into a suspend function.
 */
suspend fun <Resp : GeneratedMessageLite<Resp, *>> lndCall(
    rpcFn: (ByteArray, lndmobile.Callback) -> Unit,
    request: GeneratedMessageLite<*, *>,
    parser: Parser<Resp>,
): Resp = suspendCancellableCoroutine { cont ->
    try {
        rpcFn(request.toByteArray(), object : lndmobile.Callback {
            override fun onResponse(p0: ByteArray?) {
                try {
                    val bytes = p0 ?: ByteArray(0)
                    cont.resume(parser.parseFrom(bytes))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }

            override fun onError(p0: java.lang.Exception?) {
                cont.resumeWithException(p0 ?: RuntimeException("Unknown LND error"))
            }
        })
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        cont.resumeWithException(e)
    }
}

/**
 * Wraps an lnd-mobile streaming RPC call into a Flow.
 */
fun <Resp : GeneratedMessageLite<Resp, *>> lndStream(
    rpcFn: (ByteArray, lndmobile.RecvStream) -> Unit,
    request: GeneratedMessageLite<*, *>,
    parser: Parser<Resp>,
): Flow<Resp> = callbackFlow {
    rpcFn(request.toByteArray(), object : lndmobile.RecvStream {
        override fun onResponse(p0: ByteArray?) {
            try {
                val bytes = p0 ?: ByteArray(0)
                trySend(parser.parseFrom(bytes))
            } catch (e: Exception) {
                close(e)
            }
        }

        override fun onError(p0: java.lang.Exception?) {
            close(p0)
        }
    })
    awaitClose()
}
