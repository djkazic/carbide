package com.carbide.wallet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carbide.wallet.data.boltz.BoltzSwapManager
import com.carbide.wallet.data.boltz.ReverseSwapInfo
import com.carbide.wallet.data.boltz.ReverseSwapState
import com.carbide.wallet.data.boltz.SubmarineSwapInfo
import com.carbide.wallet.data.boltz.SubmarineSwapState
import com.carbide.wallet.data.ContactStore
import com.carbide.wallet.data.PaymentNotifier
import com.carbide.wallet.data.lnd.ChainTipProvider
import com.carbide.wallet.data.model.Contact
import com.carbide.wallet.data.lnd.ChannelPromptStore
import com.carbide.wallet.data.lnd.LndService
import com.carbide.wallet.data.lnd.LndState
import com.carbide.wallet.data.lnd.LnadHandler
import com.carbide.wallet.data.lnd.ScbBackupClient
import com.carbide.wallet.data.lnd.LnurlAuth
import com.carbide.wallet.data.lnd.LnurlAuthRequest
import com.carbide.wallet.data.lnd.LnurlAuthResult
import com.carbide.wallet.data.lnd.LnUrlPayInfo
import com.carbide.wallet.data.lnd.LnUrlResolver
import com.carbide.wallet.data.lnd.Lsps1Client
import com.carbide.wallet.data.lnd.Lsps1Info
import com.carbide.wallet.data.lnd.Lsps1Order
import com.carbide.wallet.data.lnd.WalletPasswordStore
import com.carbide.wallet.data.model.ChannelInfo
import com.carbide.wallet.data.model.DecodedInvoice
import com.carbide.wallet.data.model.FeeEstimate
import com.carbide.wallet.data.model.Transaction
import com.carbide.wallet.data.model.WalletState
import com.carbide.wallet.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val lndService: LndService,
    private val passwordStore: WalletPasswordStore,
    val channelPromptStore: ChannelPromptStore,
    private val lsps1Client: Lsps1Client,
    private val chainTipProvider: ChainTipProvider,
    val biometricLockManager: com.carbide.wallet.data.BiometricLockManager,
    private val lnadHandler: LnadHandler,
    private val scbBackupClient: ScbBackupClient,
    val lnurlAuth: LnurlAuth,
    private val boltzSwapManager: BoltzSwapManager,
    private val contactStore: ContactStore,
    private val paymentNotifier: PaymentNotifier,
    val lnUrlResolver: LnUrlResolver,
) : ViewModel() {

    val lndState: StateFlow<LndState> = lndService.state
    val bootLog: StateFlow<String> = lndService.bootLog

    private val _refreshTrigger = MutableStateFlow(0)

    private val _chainTip = MutableStateFlow(0)
    val chainTip: StateFlow<Int> = _chainTip.asStateFlow()

    fun fetchChainTip() {
        viewModelScope.launch {
            val tip = chainTipProvider.getTipHeight()
            if (tip > 0) _chainTip.value = tip
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val walletState: StateFlow<WalletState> = _refreshTrigger
        .flatMapLatest { repository.walletState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WalletState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<Transaction>> = _refreshTrigger
        .flatMapLatest { repository.transactions() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _channels = MutableStateFlow<List<ChannelInfo>>(emptyList())
    val channels: StateFlow<List<ChannelInfo>> = _channels.asStateFlow()

    fun refreshChannels() {
        viewModelScope.launch {
            _channels.value = repository.listChannels().getOrDefault(emptyList())
        }
    }

    private val _closeResult = MutableStateFlow<Result<String>?>(null)
    val closeResult: StateFlow<Result<String>?> = _closeResult.asStateFlow()

    fun closeChannel(channelPoint: String, remotePubkey: String) {
        viewModelScope.launch {
            _closeResult.value = null
            val peerOnline = repository.isPeerOnline(remotePubkey)
            _closeResult.value = repository.closeChannel(channelPoint, force = !peerOnline)
            refreshChannels()
        }
    }

    fun forceCloseChannel(channelPoint: String) {
        viewModelScope.launch {
            _closeResult.value = repository.closeChannel(channelPoint, force = true)
            refreshChannels()
        }
    }

    fun cancelInvoice(paymentHash: String) {
        viewModelScope.launch {
            try {
                val hashBytes = paymentHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                com.carbide.wallet.data.lnd.lndCall(
                    lndmobile.Lndmobile::invoicesCancelInvoice,
                    invoicesrpc.InvoicesOuterClass.CancelInvoiceMsg.newBuilder()
                        .setPaymentHash(com.google.protobuf.ByteString.copyFrom(hashBytes))
                        .build(),
                    invoicesrpc.InvoicesOuterClass.CancelInvoiceResp.parser(),
                )
                _refreshTrigger.value++
            } catch (e: Exception) {
                android.util.Log.e("WalletVM", "Cancel invoice failed", e)
            }
        }
    }

    fun clearCloseResult() {
        _closeResult.value = null
    }

    private val _sendResult = MutableStateFlow<Result<Transaction>?>(null)
    val sendResult: StateFlow<Result<Transaction>?> = _sendResult.asStateFlow()

    private val _decodedInvoice = MutableStateFlow<Result<DecodedInvoice>?>(null)
    val decodedInvoice: StateFlow<Result<DecodedInvoice>?> = _decodedInvoice.asStateFlow()

    private val _invoiceResult = MutableStateFlow<Result<Pair<String, String>>?>(null)
    val invoiceResult: StateFlow<Result<Pair<String, String>>?> = _invoiceResult.asStateFlow()

    private var needsScbRestore = false

    private val _seedWords = MutableStateFlow<List<String>>(emptyList())
    val seedWords: StateFlow<List<String>> = _seedWords.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    fun startLnd() {
        lndService.start()
    }

    fun startBackupSubscription() {
        lndService.startBackupSubscription()
    }

    fun startScbAutoBackup() {
        scbBackupClient.startAutoBackup()
    }

    fun startLnadHandler() {
        lnadHandler.start()
    }

    fun startPaymentNotifications() {
        paymentNotifier.start()
    }

    fun startInvoiceSubscription() {
        lndService.subscribeInvoiceUpdates {
            // Force immediate refresh of transactions and wallet state
            _refreshTrigger.value++
        }
    }

    fun generateSeed() {
        viewModelScope.launch {
            try {
                _seedWords.value = lndService.genSeed()
            } catch (e: Exception) {
                _setupError.value = e.message
            }
        }
    }

    fun initWallet(password: String) {
        viewModelScope.launch {
            try {
                _setupError.value = null
                lndService.initWallet(password, _seedWords.value)
                passwordStore.setPassword(password)
            } catch (e: Exception) {
                _setupError.value = e.message
            }
        }
    }

    fun restoreWallet(seed: List<String>, channelBackup: ByteArray? = null) {
        viewModelScope.launch {
            try {
                _setupError.value = null
                val password = java.util.UUID.randomUUID().toString()

                var scb = channelBackup
                if (scb == null) {
                    // Flag to try server restore once LND is running
                    needsScbRestore = true
                }

                lndService.initWallet(
                    password = password,
                    seed = seed,
                    recoveryWindow = 2500,
                    channelBackup = scb,
                )
                passwordStore.setPassword(password)
            } catch (e: Exception) {
                _setupError.value = e.message
            }
        }
    }

    /**
     * Try to retrieve and apply SCB from backup server.
     * Call after wallet is running and node pubkey is available.
     */
    fun maybeRestoreScb() {
        if (!needsScbRestore) return
        needsScbRestore = false
        tryRestoreScbFromServer()
    }

    fun tryRestoreScbFromServer() {
        viewModelScope.launch {
            try {
                val info = com.carbide.wallet.data.lnd.lndCall(
                    lndmobile.Lndmobile::getInfo,
                    lnrpc.LightningOuterClass.GetInfoRequest.getDefaultInstance(),
                    lnrpc.LightningOuterClass.GetInfoResponse.parser(),
                )
                val scb = scbBackupClient.retrieveBackup(info.identityPubkey)
                if (scb != null) {
                    com.carbide.wallet.data.lnd.lndCall(
                        lndmobile.Lndmobile::restoreChannelBackups,
                        lnrpc.LightningOuterClass.RestoreChanBackupRequest.newBuilder()
                            .setMultiChanBackup(com.google.protobuf.ByteString.copyFrom(scb))
                            .build(),
                        lnrpc.LightningOuterClass.RestoreBackupResponse.parser(),
                    )
                    android.util.Log.d("WalletVM", "SCB restored from server!")
                }
            } catch (e: Exception) {
                android.util.Log.d("WalletVM", "SCB server restore: ${e.message}")
            }
        }
    }

    private val _scbExport = MutableStateFlow<Result<ByteArray>?>(null)
    val scbExport: StateFlow<Result<ByteArray>?> = _scbExport.asStateFlow()

    fun exportChannelBackup() {
        viewModelScope.launch {
            _scbExport.value = runCatching { lndService.exportChannelBackup() }
        }
    }

    fun tryAutoUnlock() {
        val password = passwordStore.getPassword() ?: return
        viewModelScope.launch {
            try {
                lndService.unlockWallet(password)
            } catch (e: Exception) {
                // Auto-unlock failed — user will need to enter password manually
                _setupError.value = "Auto-unlock failed: ${e.message}"
            }
        }
    }

    fun decodePayReq(paymentRequest: String) {
        viewModelScope.launch {
            _decodedInvoice.value = repository.decodePayReq(paymentRequest)
        }
    }

    fun sendPayment(paymentRequest: String) {
        viewModelScope.launch {
            _sendResult.value = repository.sendPayment(paymentRequest)
        }
    }

    private val _lnUrlInfo = MutableStateFlow<Result<LnUrlPayInfo>?>(null)
    val lnUrlInfo: StateFlow<Result<LnUrlPayInfo>?> = _lnUrlInfo.asStateFlow()

    fun resolveLnAddress(address: String) {
        viewModelScope.launch {
            _lnUrlInfo.value = runCatching { lnUrlResolver.resolve(address) }
        }
    }

    fun payLnUrl(callback: String, amountSat: Long) {
        viewModelScope.launch {
            try {
                val invoice = lnUrlResolver.fetchInvoice(callback, amountSat * 1000)
                _sendResult.value = repository.sendPayment(invoice)
            } catch (e: Exception) {
                _sendResult.value = Result.failure(e)
            }
        }
    }

    fun clearSend() {
        _decodedInvoice.value = null
        _sendResult.value = null
        _lnUrlInfo.value = null
    }

    fun createInvoice(amountSats: Long, memo: String) {
        viewModelScope.launch {
            _invoiceResult.value = repository.createInvoice(amountSats, memo)
        }
    }

    fun clearInvoice() {
        _invoiceResult.value = null
    }

    // On-chain

    private val _onChainAddress = MutableStateFlow<Result<String>?>(null)
    val onChainAddress: StateFlow<Result<String>?> = _onChainAddress.asStateFlow()

    private val _feeEstimate = MutableStateFlow<Result<FeeEstimate>?>(null)
    val feeEstimate: StateFlow<Result<FeeEstimate>?> = _feeEstimate.asStateFlow()

    private val _sendOnChainResult = MutableStateFlow<Result<String>?>(null)
    val sendOnChainResult: StateFlow<Result<String>?> = _sendOnChainResult.asStateFlow()

    fun newAddress() {
        viewModelScope.launch {
            _onChainAddress.value = repository.newAddress()
        }
    }

    fun clearOnChainAddress() {
        _onChainAddress.value = null
    }

    fun estimateFee(address: String, amountSats: Long) {
        viewModelScope.launch {
            _feeEstimate.value = repository.estimateFee(address, amountSats)
        }
    }

    fun sendOnChain(address: String, amountSats: Long, satPerVbyte: Long? = null) {
        viewModelScope.launch {
            val rate = satPerVbyte ?: chainTipProvider.getHourFeeRate()
            _sendOnChainResult.value = repository.sendOnChain(address, amountSats, rate)
        }
    }

    fun clearOnChainSend() {
        _feeEstimate.value = null
        _sendOnChainResult.value = null
    }

    // LSPS1 channel purchase

    private val _lspInfo = MutableStateFlow<Result<Lsps1Info>?>(null)
    val lspInfo: StateFlow<Result<Lsps1Info>?> = _lspInfo.asStateFlow()

    private val _lspOrder = MutableStateFlow<Result<Lsps1Order>?>(null)
    val lspOrder: StateFlow<Result<Lsps1Order>?> = _lspOrder.asStateFlow()

    fun connectToLsp() {
        viewModelScope.launch {
            try {
                repository.connectPeer(
                    "028c589131fae8c7e2103326542d568373019b50a9eb376a139a330c8545efb79a",
                    "100.0.242.58:9735",
                ).getOrThrow()
            } catch (_: Exception) {
                // Best-effort — may already be connected or unreachable
            }
        }
    }

    fun resumeActiveOrder(pubkey: String, host: String) {
        val orderId = channelPromptStore.getActiveOrderId() ?: return
        viewModelScope.launch {
            // LND may not be fully ready for custom messages yet — retry
            for (attempt in 1..5) {
                try {
                    kotlinx.coroutines.delay(3000L * attempt)
                    repository.connectPeer(pubkey, host)
                    lsps1Client.startListening()
                    val order = lsps1Client.getOrder(pubkey, orderId)
                    _lspOrder.value = Result.success(order)
                    if (order.isCompleted || order.isFailed) {
                        channelPromptStore.setActiveOrderId(null)
                    }
                    return@launch
                } catch (e: Exception) {
                    android.util.Log.w("WalletVM", "Resume order attempt $attempt failed: ${e.message}")
                }
            }
            android.util.Log.e("WalletVM", "Resume order failed after retries")
            // Don't leave lspOrder null — the card needs to know there's an active order
            // even if we can't poll it right now
            _lspOrder.value = Result.failure(RuntimeException("Could not reach LSP"))
        }
    }

    fun connectAndGetLspInfo(pubkey: String, host: String) {
        viewModelScope.launch {
            try {
                _lspInfo.value = null
                _lspOrder.value = null
                repository.connectPeer(pubkey, host).getOrThrow()
                lsps1Client.startListening()
                // Retry getInfo a few times — peer may not be fully ready for custom messages
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        _lspInfo.value = Result.success(lsps1Client.getInfo(pubkey))
                        return@launch
                    } catch (e: Exception) {
                        lastError = e
                        kotlinx.coroutines.delay(2000L * attempt)
                    }
                }
                _lspInfo.value = Result.failure(lastError!!)
            } catch (e: Exception) {
                _lspInfo.value = Result.failure(e)
            }
        }
    }

    fun createLspOrder(
        pubkey: String,
        lspBalanceSat: Long,
        clientBalanceSat: Long,
        channelExpiryBlocks: Int,
    ) {
        viewModelScope.launch {
            try {
                // Get a refund address
                val refundAddr = repository.newAddress().getOrThrow()
                android.util.Log.d("WalletVM", "Creating LSP order: lsp=$lspBalanceSat client=$clientBalanceSat refund=$refundAddr")
                val order = lsps1Client.createOrder(
                    peerPubkey = pubkey,
                    lspBalanceSat = lspBalanceSat,
                    clientBalanceSat = clientBalanceSat,
                    channelExpiryBlocks = channelExpiryBlocks,
                    refundAddress = refundAddr,
                )
                android.util.Log.d("WalletVM", "LSP order result: id=${order.orderId} state=${order.orderState} bolt11=${order.bolt11Invoice?.take(30)}")
                _lspOrder.value = Result.success(order)
                channelPromptStore.setActiveOrderId(order.orderId)
            } catch (e: Exception) {
                android.util.Log.e("WalletVM", "LSP order failed", e)
                _lspOrder.value = Result.failure(e)
            }
        }
    }

    fun pollOrderStatus(pubkey: String, orderId: String) {
        viewModelScope.launch {
            try {
                _lspOrder.value = Result.success(lsps1Client.getOrder(pubkey, orderId))
            } catch (e: Exception) {
                _lspOrder.value = Result.failure(e)
            }
        }
    }

    fun clearLspState() {
        _lspInfo.value = null
        _lspOrder.value = null
        channelPromptStore.setActiveOrderId(null)
    }

    // Boltz swaps

    private val _swapInfo = MutableStateFlow<Result<ReverseSwapInfo>?>(null)
    val swapInfo: StateFlow<Result<ReverseSwapInfo>?> = _swapInfo.asStateFlow()

    private val _swapState = MutableStateFlow<Result<ReverseSwapState>?>(null)
    val swapState: StateFlow<Result<ReverseSwapState>?> = _swapState.asStateFlow()

    private val _swapStatus = MutableStateFlow<String?>(null)
    val swapStatus: StateFlow<String?> = _swapStatus.asStateFlow()

    fun fetchSwapInfo() {
        viewModelScope.launch {
            _swapInfo.value = runCatching { boltzSwapManager.getSwapInfo() }
        }
    }

    fun startReverseSwap(amountSat: Long) {
        viewModelScope.launch {
            try {
                // 1. Create swap
                _swapStatus.value = "Creating swap..."
                val swap = boltzSwapManager.createReverseSwap(amountSat)
                _swapState.value = Result.success(swap)

                // 2. Pay the invoice (async — doesn't block until settlement)
                _swapStatus.value = "Paying invoice..."
                boltzSwapManager.payInvoiceAsync(swap.invoice)

                // 3. Wait for Boltz lockup (happens after Boltz sees our HTLC)
                _swapStatus.value = "Waiting for lockup..."
                boltzSwapManager.waitForLockup(swap.id)

                // 4. Submit preimage to settle
                _swapStatus.value = "Claiming funds..."
                val txid = boltzSwapManager.cooperativeClaim(swap)

                _swapStatus.value = "transaction.claimed"
                boltzSwapManager.clearReverseSwap()
                android.util.Log.d("WalletVM", "Swap complete! Claim txid: $txid")
            } catch (e: Exception) {
                android.util.Log.e("WalletVM", "Swap failed", e)
                _swapState.value = Result.failure(e)
            }
        }
    }

    fun resumeSwap() {
        val swap = boltzSwapManager.loadPendingReverseSwap() ?: return
        viewModelScope.launch {
            try {
                _swapState.value = Result.success(swap)
                _swapStatus.value = "Resuming..."

                // Check current status
                val status = boltzSwapManager.getSwapStatus(swap.id)
                _swapStatus.value = status
                android.util.Log.d("WalletVM", "Resume swap ${swap.id}: status=$status")

                when {
                    status == "transaction.mempool" || status == "transaction.confirmed" -> {
                        _swapStatus.value = "Claiming funds..."
                        val txid = boltzSwapManager.cooperativeClaim(swap)
                        _swapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearReverseSwap()
                        android.util.Log.d("WalletVM", "Resume claim complete: $txid")
                    }
                    status == "transaction.claimed" -> {
                        _swapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearReverseSwap()
                    }
                    status == "invoice.settled" -> {
                        // Invoice settled but on-chain claim still needed
                        _swapStatus.value = "Claiming funds..."
                        val txid = boltzSwapManager.cooperativeClaim(swap)
                        _swapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearReverseSwap()
                        android.util.Log.d("WalletVM", "Resume claim complete: $txid")
                    }
                    status.contains("error") || status.contains("failed") || status == "swap.expired" -> {
                        _swapState.value = Result.failure(RuntimeException("Swap $status"))
                        boltzSwapManager.clearReverseSwap()
                    }
                    else -> {
                        // Still waiting — pay invoice if not already in flight and wait for lockup
                        _swapStatus.value = "Paying invoice..."
                        boltzSwapManager.payInvoiceAsync(swap.invoice)
                        boltzSwapManager.waitForLockup(swap.id)
                        _swapStatus.value = "Claiming funds..."
                        val txid = boltzSwapManager.cooperativeClaim(swap)
                        _swapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearReverseSwap()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WalletVM", "Resume swap failed", e)
                _swapState.value = Result.failure(e)
            }
        }
    }

    // Submarine swap (On-chain → Lightning)

    private val _subSwapInfo = MutableStateFlow<Result<SubmarineSwapInfo>?>(null)
    val subSwapInfo: StateFlow<Result<SubmarineSwapInfo>?> = _subSwapInfo.asStateFlow()

    private val _subSwapState = MutableStateFlow<Result<SubmarineSwapState>?>(null)
    val subSwapState: StateFlow<Result<SubmarineSwapState>?> = _subSwapState.asStateFlow()

    private val _subSwapStatus = MutableStateFlow<String?>(null)
    val subSwapStatus: StateFlow<String?> = _subSwapStatus.asStateFlow()

    fun fetchSubSwapInfo() {
        viewModelScope.launch {
            _subSwapInfo.value = runCatching { boltzSwapManager.getSubmarineSwapInfo() }
        }
    }

    fun startSubmarineSwap(amountSat: Long) {
        viewModelScope.launch {
            try {
                _subSwapStatus.value = "Creating swap..."
                val swap = boltzSwapManager.createSubmarineSwap(amountSat)
                _subSwapState.value = Result.success(swap)

                _subSwapStatus.value = "Sending on-chain funds..."
                boltzSwapManager.sendSubmarineLockup(swap.address, swap.expectedAmount)

                _subSwapStatus.value = "Waiting for invoice payment..."
                boltzSwapManager.waitAndSignSubmarineClaim(swap)

                _subSwapStatus.value = "transaction.claimed"
                boltzSwapManager.clearSubmarineSwap()
            } catch (e: Exception) {
                android.util.Log.e("WalletVM", "Submarine swap failed", e)
                _subSwapState.value = Result.failure(e)
            }
        }
    }

    fun resumeSubmarineSwap() {
        val swap = boltzSwapManager.loadPendingSubmarineSwap() ?: return
        viewModelScope.launch {
            try {
                _subSwapState.value = Result.success(swap)
                _subSwapStatus.value = "Resuming..."

                val status = boltzSwapManager.getSwapStatus(swap.id)
                android.util.Log.d("WalletVM", "Resume submarine swap ${swap.id}: status=$status")

                when {
                    status == "transaction.claimed" || status == "invoice.settled" -> {
                        _subSwapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearSubmarineSwap()
                    }
                    status == "transaction.claim.pending" -> {
                        _subSwapStatus.value = "Signing cooperative claim..."
                        boltzSwapManager.waitAndSignSubmarineClaim(swap)
                        _subSwapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearSubmarineSwap()
                    }
                    status == "swap.expired" || status == "invoice.failedToPay" -> {
                        // Need to refund
                        _subSwapStatus.value = "Refunding..."
                        boltzSwapManager.cooperativeRefund(swap)
                        _subSwapStatus.value = "refunded"
                        boltzSwapManager.clearSubmarineSwap()
                    }
                    status.contains("error") || status.contains("failed") -> {
                        // Try refund
                        _subSwapStatus.value = "Refunding..."
                        try {
                            boltzSwapManager.cooperativeRefund(swap)
                            _subSwapStatus.value = "refunded"
                        } catch (e: Exception) {
                            _subSwapState.value = Result.failure(RuntimeException("Swap failed ($status), refund failed: ${e.message}"))
                        }
                        boltzSwapManager.clearSubmarineSwap()
                    }
                    else -> {
                        // Still in progress — continue monitoring
                        _subSwapStatus.value = "Waiting for invoice payment..."
                        boltzSwapManager.waitAndSignSubmarineClaim(swap)
                        _subSwapStatus.value = "transaction.claimed"
                        boltzSwapManager.clearSubmarineSwap()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WalletVM", "Resume submarine swap failed", e)
                _subSwapState.value = Result.failure(e)
            }
        }
    }

    fun clearSubSwapState() {
        _subSwapInfo.value = null
        _subSwapState.value = null
        _subSwapStatus.value = null
    }

    fun clearSwapState() {
        _swapInfo.value = null
        _swapState.value = null
        _swapStatus.value = null
    }

    // LNURL-Auth

    private val _authResult = MutableStateFlow<Result<LnurlAuthResult>?>(null)
    val authResult: StateFlow<Result<LnurlAuthResult>?> = _authResult.asStateFlow()

    fun authenticateLnurl(request: LnurlAuthRequest) {
        viewModelScope.launch {
            _authResult.value = runCatching { lnurlAuth.authenticate(request) }
        }
    }

    fun clearAuthResult() {
        _authResult.value = null
    }

    // Contacts
    val contacts: StateFlow<List<Contact>> = contactStore.contacts

    fun addContact(contact: Contact) = contactStore.add(contact)
    fun removeContact(contact: Contact) = contactStore.remove(contact)

    fun dismissChannelPrompt() {
        channelPromptStore.dismiss()
        channelPromptStore.setActiveOrderId(null)
    }
}
