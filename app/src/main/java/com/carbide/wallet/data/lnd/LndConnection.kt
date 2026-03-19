package com.carbide.wallet.data.lnd

import com.carbide.wallet.data.model.ChannelInfo
import com.carbide.wallet.data.model.DecodedInvoice
import com.carbide.wallet.data.model.FeeEstimate
import com.carbide.wallet.data.model.Transaction
import com.carbide.wallet.data.model.WalletState
import kotlinx.coroutines.flow.Flow

interface LndConnection {
    fun walletState(): Flow<WalletState>
    fun transactions(): Flow<List<Transaction>>
    suspend fun decodePayReq(paymentRequest: String): Result<DecodedInvoice>
    suspend fun sendPayment(paymentRequest: String): Result<Transaction>
    suspend fun createInvoice(amountSats: Long, memo: String): Result<Pair<String, String>>
    suspend fun newAddress(): Result<String>
    suspend fun sendOnChain(address: String, amountSats: Long, satPerVbyte: Long): Result<String>
    suspend fun estimateFee(address: String, amountSats: Long): Result<FeeEstimate>
    suspend fun listChannels(): Result<List<ChannelInfo>>
    suspend fun closeChannel(channelPoint: String, force: Boolean): Result<String>
    suspend fun isPeerOnline(pubkey: String): Boolean
    suspend fun connectPeer(pubkey: String, host: String): Result<Unit>
    suspend fun openChannel(pubkey: String, localAmountSats: Long): Result<String>
}
