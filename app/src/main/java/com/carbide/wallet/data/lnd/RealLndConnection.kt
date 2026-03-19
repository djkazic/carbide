package com.carbide.wallet.data.lnd

import com.carbide.wallet.data.model.ChannelInfo
import com.carbide.wallet.data.model.DecodedInvoice
import com.carbide.wallet.data.model.FeeEstimate
import com.carbide.wallet.data.model.Transaction
import com.carbide.wallet.data.model.WalletState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import lnrpc.LightningOuterClass as LN
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealLndConnection @Inject constructor(
    private val lndService: LndService,
) : LndConnection {

    override fun walletState(): Flow<WalletState> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val info = lndCall(
                    lndmobile.Lndmobile::getInfo,
                    LN.GetInfoRequest.getDefaultInstance(),
                    LN.GetInfoResponse.parser(),
                )
                val walletBal = lndCall(
                    lndmobile.Lndmobile::walletBalance,
                    LN.WalletBalanceRequest.getDefaultInstance(),
                    LN.WalletBalanceResponse.parser(),
                )
                val chanBal = lndCall(
                    lndmobile.Lndmobile::channelBalance,
                    LN.ChannelBalanceRequest.getDefaultInstance(),
                    LN.ChannelBalanceResponse.parser(),
                )
                val pendingChanBal = chanBal.pendingOpenLocalBalance.sat +
                    chanBal.unsettledLocalBalance.sat
                // Get limbo balance from pending channels (closing channels)
                val pendingChans = try {
                    lndCall(
                        lndmobile.Lndmobile::pendingChannels,
                        LN.PendingChannelsRequest.getDefaultInstance(),
                        LN.PendingChannelsResponse.parser(),
                    )
                } catch (_: Exception) { null }
                val limboBalance = pendingChans?.totalLimboBalance ?: 0

                emit(
                    WalletState(
                        balanceSats = walletBal.confirmedBalance + chanBal.localBalance.sat,
                        channelBalanceSats = chanBal.localBalance.sat,
                        onchainBalanceSats = walletBal.confirmedBalance,
                        pendingSats = walletBal.unconfirmedBalance,
                        pendingLightningSats = pendingChanBal + limboBalance,
                        syncedToChain = info.syncedToChain,
                        syncedToGraph = info.syncedToGraph,
                        blockHeight = info.blockHeight,
                        bestHeaderTimestamp = info.bestHeaderTimestamp,
                        numActiveChannels = info.numActiveChannels,
                        numPendingChannels = info.numPendingChannels,
                        numPeers = info.numPeers,
                    )
                )
            } catch (_: Exception) {
                emit(WalletState())
            }
            delay(5_000)
        }
    }

    override fun transactions(): Flow<List<Transaction>> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val onChain = lndCall(
                    lndmobile.Lndmobile::getTransactions,
                    LN.GetTransactionsRequest.getDefaultInstance(),
                    LN.TransactionDetails.parser(),
                )

                val payments = lndCall(
                    lndmobile.Lndmobile::listPayments,
                    LN.ListPaymentsRequest.newBuilder()
                        .setIncludeIncomplete(true)
                        .build(),
                    LN.ListPaymentsResponse.parser(),
                )

                val invoices = lndCall(
                    lndmobile.Lndmobile::listInvoices,
                    LN.ListInvoiceRequest.newBuilder()
                        .setNumMaxInvoices(50)
                        .setReversed(true)
                        .build(),
                    LN.ListInvoiceResponse.parser(),
                )

                val txList = mutableListOf<Transaction>()

                for (tx in onChain.transactionsList) {
                    txList.add(
                        Transaction(
                            id = tx.txHash,
                            amountSats = kotlin.math.abs(tx.amount),
                            direction = if (tx.amount >= 0) Transaction.Direction.RECEIVED
                            else Transaction.Direction.SENT,
                            status = if (tx.numConfirmations > 0) Transaction.Status.SETTLED
                            else Transaction.Status.PENDING,
                            type = Transaction.Type.ONCHAIN,
                            memo = tx.label.ifEmpty { "On-chain" },
                            timestamp = Instant.ofEpochSecond(tx.timeStamp),
                            feeSats = tx.totalFees,
                        )
                    )
                }

                for (pay in payments.paymentsList) {
                    if (pay.status == LN.Payment.PaymentStatus.FAILED) continue
                    txList.add(
                        Transaction(
                            id = pay.paymentHash,
                            amountSats = pay.valueSat,
                            direction = Transaction.Direction.SENT,
                            status = when (pay.status) {
                                LN.Payment.PaymentStatus.SUCCEEDED -> Transaction.Status.SETTLED
                                else -> Transaction.Status.PENDING
                            },
                            type = Transaction.Type.LIGHTNING,
                            memo = "",
                            timestamp = Instant.ofEpochSecond(pay.creationDate),
                            feeSats = pay.feeSat,
                            preimage = pay.paymentPreimage,
                        )
                    )
                }

                for (inv in invoices.invoicesList) {
                    if (inv.state == LN.Invoice.InvoiceState.CANCELED) continue
                    txList.add(
                        Transaction(
                            id = inv.rHash.toByteArray().joinToString("") { "%02x".format(it) },
                            amountSats = if (inv.amtPaidSat > 0) inv.amtPaidSat else inv.value,
                            direction = Transaction.Direction.RECEIVED,
                            status = when (inv.state) {
                                LN.Invoice.InvoiceState.SETTLED -> Transaction.Status.SETTLED
                                else -> Transaction.Status.PENDING
                            },
                            type = Transaction.Type.LIGHTNING,
                            memo = inv.memo,
                            timestamp = Instant.ofEpochSecond(
                                if (inv.settleDate > 0) inv.settleDate else inv.creationDate
                            ),
                            preimage = if (inv.rPreimage.size() > 0)
                                inv.rPreimage.toByteArray().joinToString("") { "%02x".format(it) }
                            else "",
                        )
                    )
                }

                txList.sortByDescending { it.timestamp }
                emit(txList)
            } catch (_: Exception) {
                emit(emptyList())
            }
            delay(10_000)
        }
    }

    override suspend fun decodePayReq(paymentRequest: String): Result<DecodedInvoice> =
        runCatching {
            val request = LN.PayReqString.newBuilder()
                .setPayReq(paymentRequest.trim())
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::decodePayReq,
                request,
                LN.PayReq.parser(),
            )
            DecodedInvoice(
                paymentRequest = paymentRequest.trim(),
                amountSats = response.numSatoshis,
                description = response.description,
                destination = response.destination,
                expiry = response.expiry,
                timestamp = response.timestamp,
            )
        }

    override suspend fun sendPayment(paymentRequest: String): Result<Transaction> =
        runCatching {
            val request = LN.SendRequest.newBuilder()
                .setPaymentRequest(paymentRequest)
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::sendPaymentSync,
                request,
                LN.SendResponse.parser(),
            )
            if (response.paymentError.isNotEmpty()) {
                error(response.paymentError)
            }
            Transaction(
                id = response.paymentHash.toByteArray().joinToString("") { "%02x".format(it) },
                amountSats = 0,
                direction = Transaction.Direction.SENT,
                status = Transaction.Status.SETTLED,
                type = Transaction.Type.LIGHTNING,
                memo = "",
                timestamp = Instant.now(),
            )
        }

    override suspend fun createInvoice(amountSats: Long, memo: String): Result<Pair<String, String>> =
        runCatching {
            val request = LN.Invoice.newBuilder()
                .setValue(amountSats)
                .setMemo(memo)
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::addInvoice,
                request,
                LN.AddInvoiceResponse.parser(),
            )
            val paymentHash = response.rHash.toByteArray().joinToString("") { "%02x".format(it) }
            Pair(response.paymentRequest, paymentHash)
        }

    override suspend fun newAddress(): Result<String> =
        runCatching {
            val request = LN.NewAddressRequest.newBuilder()
                .setType(LN.AddressType.TAPROOT_PUBKEY)
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::newAddress,
                request,
                LN.NewAddressResponse.parser(),
            )
            response.address
        }

    override suspend fun sendOnChain(
        address: String,
        amountSats: Long,
        satPerVbyte: Long,
    ): Result<String> =
        runCatching {
            val request = LN.SendCoinsRequest.newBuilder()
                .setAddr(address)
                .setAmount(amountSats)
                .setSatPerVbyte(satPerVbyte)
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::sendCoins,
                request,
                LN.SendCoinsResponse.parser(),
            )
            response.txid
        }

    override suspend fun estimateFee(address: String, amountSats: Long): Result<FeeEstimate> =
        runCatching {
            val request = LN.EstimateFeeRequest.newBuilder()
                .putAddrToAmount(address, amountSats)
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::estimateFee,
                request,
                LN.EstimateFeeResponse.parser(),
            )
            FeeEstimate(
                feeSats = response.feeSat,
                satPerVbyte = response.satPerVbyte,
            )
        }

    override suspend fun listChannels(): Result<List<ChannelInfo>> =
        runCatching {
            val response = lndCall(
                lndmobile.Lndmobile::listChannels,
                LN.ListChannelsRequest.getDefaultInstance(),
                LN.ListChannelsResponse.parser(),
            )
            response.channelsList.map { ch ->
                android.util.Log.d("ChannelDebug",
                    "capacity=${ch.capacity} local=${ch.localBalance} remote=${ch.remoteBalance} " +
                    "localReserve=${ch.localConstraints?.chanReserveSat} remoteReserve=${ch.remoteConstraints?.chanReserveSat} " +
                    "commitFee=${ch.commitFee} feePerKw=${ch.feePerKw} commitmentType=${ch.commitmentType}")
                ChannelInfo(
                    chanId = ch.chanId,
                    channelPoint = ch.channelPoint,
                    remotePubkey = ch.remotePubkey,
                    capacity = ch.capacity,
                    localBalance = ch.localBalance,
                    remoteBalance = ch.remoteBalance,
                    localReserveSat = ch.localConstraints?.chanReserveSat
                        ?: ch.localChanReserveSat,
                    remoteReserveSat = ch.remoteConstraints?.chanReserveSat
                        ?: ch.remoteChanReserveSat,
                    remoteDustLimitSat = ch.remoteConstraints?.dustLimitSat ?: 354,
                    commitFeeSat = ch.commitFee,
                    feePerKw = ch.feePerKw,
                    localCsvDelay = ch.localConstraints?.csvDelay?.toInt() ?: 0,
                    remoteCsvDelay = ch.remoteConstraints?.csvDelay?.toInt() ?: 0,
                    active = ch.active,
                    private = ch.getPrivate(),
                    numPendingHtlcs = ch.pendingHtlcsCount,
                    pendingHtlcsTotalSat = ch.pendingHtlcsList.sumOf { it.amount },
                    pendingIncomingHtlcsSat = ch.pendingHtlcsList.filter { it.incoming }.sumOf { it.amount },
                    pendingOutgoingHtlcsSat = ch.pendingHtlcsList.filter { !it.incoming }.sumOf { it.amount },
                    htlcs = ch.pendingHtlcsList.map { htlc ->
                        com.carbide.wallet.data.model.HtlcInfo(
                            incoming = htlc.incoming,
                            amount = htlc.amount,
                            hashLock = htlc.hashLock.toByteArray().joinToString("") { "%02x".format(it) },
                            expirationHeight = htlc.expirationHeight,
                        )
                    },
                    totalSatoshisSent = ch.totalSatoshisSent,
                    totalSatoshisReceived = ch.totalSatoshisReceived,
                )
            }
        }

    override suspend fun closeChannel(channelPoint: String, force: Boolean): Result<String> =
        runCatching {
            val parts = channelPoint.split(":")
            val txid = parts[0]
            val outputIndex = parts[1].toInt()

            val cp = LN.ChannelPoint.newBuilder()
                .setFundingTxidStr(txid)
                .setOutputIndex(outputIndex)
                .build()
            val request = LN.CloseChannelRequest.newBuilder()
                .setChannelPoint(cp)
                .setForce(force)
                .build()

            val flow = lndStream(
                lndmobile.Lndmobile::closeChannel,
                request,
                LN.CloseStatusUpdate.parser(),
            )

            // Get first update (close pending) and return
            val update = flow.first()
            val pending = update.closePending
            if (pending != null) {
                val txidBytes = pending.txid.toByteArray()
                val reversed = ByteArray(txidBytes.size)
                for (i in txidBytes.indices) {
                    reversed[txidBytes.size - 1 - i] = txidBytes[i]
                }
                reversed.joinToString("") { "%02x".format(it) }
            } else {
                "pending"
            }
        }

    override suspend fun isPeerOnline(pubkey: String): Boolean =
        try {
            val response = lndCall(
                lndmobile.Lndmobile::listPeers,
                LN.ListPeersRequest.getDefaultInstance(),
                LN.ListPeersResponse.parser(),
            )
            response.peersList.any { it.pubKey == pubkey }
        } catch (_: Exception) {
            false
        }

    override suspend fun connectPeer(pubkey: String, host: String): Result<Unit> =
        runCatching {
            val addr = LN.LightningAddress.newBuilder()
                .setPubkey(pubkey)
                .setHost(host)
                .build()
            val request = LN.ConnectPeerRequest.newBuilder()
                .setAddr(addr)
                .setPerm(true)
                .build()
            try {
                lndCall(
                    lndmobile.Lndmobile::connectPeer,
                    request,
                    LN.ConnectPeerResponse.parser(),
                )
            } catch (e: Exception) {
                // "already connected" is fine
                if (!e.message.orEmpty().contains("already connected")) throw e
            }
        }

    override suspend fun openChannel(pubkey: String, localAmountSats: Long): Result<String> =
        runCatching {
            val request = LN.OpenChannelRequest.newBuilder()
                .setNodePubkeyString(pubkey)
                .setLocalFundingAmount(localAmountSats)
                .setPushSat(0)
                .build()
            val response = lndCall(
                lndmobile.Lndmobile::openChannelSync,
                request,
                LN.ChannelPoint.parser(),
            )
            "${response.fundingTxidStr}:${response.outputIndex}"
        }
}
