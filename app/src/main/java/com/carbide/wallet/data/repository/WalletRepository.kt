package com.carbide.wallet.data.repository

import com.carbide.wallet.data.lnd.LndConnection
import com.carbide.wallet.data.model.ChannelInfo
import com.carbide.wallet.data.model.DecodedInvoice
import com.carbide.wallet.data.model.FeeEstimate
import com.carbide.wallet.data.model.Transaction
import com.carbide.wallet.data.model.WalletState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletRepository @Inject constructor(
    private val lnd: LndConnection,
) {
    fun walletState(): Flow<WalletState> = lnd.walletState()

    fun transactions(): Flow<List<Transaction>> = lnd.transactions()

    suspend fun decodePayReq(paymentRequest: String): Result<DecodedInvoice> =
        lnd.decodePayReq(paymentRequest)

    suspend fun sendPayment(paymentRequest: String): Result<Transaction> =
        lnd.sendPayment(paymentRequest)

    suspend fun createInvoice(amountSats: Long, memo: String): Result<Pair<String, String>> =
        lnd.createInvoice(amountSats, memo)

    suspend fun newAddress(): Result<String> =
        lnd.newAddress()

    suspend fun sendOnChain(address: String, amountSats: Long, satPerVbyte: Long): Result<String> =
        lnd.sendOnChain(address, amountSats, satPerVbyte)

    suspend fun estimateFee(address: String, amountSats: Long): Result<FeeEstimate> =
        lnd.estimateFee(address, amountSats)

    suspend fun listChannels(): Result<List<ChannelInfo>> =
        lnd.listChannels()

    suspend fun closeChannel(channelPoint: String, force: Boolean): Result<String> =
        lnd.closeChannel(channelPoint, force)

    suspend fun isPeerOnline(pubkey: String): Boolean =
        lnd.isPeerOnline(pubkey)

    suspend fun connectPeer(pubkey: String, host: String): Result<Unit> =
        lnd.connectPeer(pubkey, host)

    suspend fun openChannel(pubkey: String, localAmountSats: Long): Result<String> =
        lnd.openChannel(pubkey, localAmountSats)
}
