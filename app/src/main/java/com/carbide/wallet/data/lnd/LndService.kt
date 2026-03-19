package com.carbide.wallet.data.lnd

import android.content.Context
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lnrpc.LightningOuterClass
import lnrpc.Stateservice
import lnrpc.Walletunlocker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LndService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LndState>(LndState.NotStarted)
    val state: StateFlow<LndState> = _state.asStateFlow()

    private val _bootLog = MutableStateFlow<String>("")
    val bootLog: StateFlow<String> = _bootLog.asStateFlow()

    fun updateBootLog(line: String) {
        _bootLog.value = line
    }

    val lndDir by lazy { context.filesDir.resolve("lnd").also { it.mkdirs() } }

    fun start() {
        if (_state.value != LndState.NotStarted && _state.value !is LndState.Error) return
        _state.value = LndState.Starting

        // Start foreground service to prevent Android from killing LND
        try {
            val intent = android.content.Intent(context, com.carbide.wallet.LndForegroundService::class.java)
            context.startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.w("LndService", "Failed to start foreground service", e)
        }

        val args = buildString {
            append("--lnddir=${lndDir.absolutePath}")
            append(" --bitcoin.active")
            append(" --bitcoin.mainnet")
            append(" --bitcoin.node=neutrino")
            append(" --neutrino.connect=node.eldamar.icu")
            append(" --neutrino.connect=btcd1.lnolymp.us")
            append(" --neutrino.connect=btcd2.lnolymp.us")
            append(" --routing.assumechanvalid")
            append(" --accept-keysend")
            append(" --tlsdisableautofill")
            append(" --nolisten")
            append(" --norest")
            append(" --fee.url=https://nodes.lightning.computer/fees/v1/btc-fee-estimates.json")
            append(" --debuglevel=info")
        }

        // Start watching LND logs for boot progress
        scope.launch { watchLndLogs() }

        lndmobile.Lndmobile.start(args, object : lndmobile.Callback {
            override fun onResponse(p0: ByteArray?) {
                scope.launch { subscribeState() }
            }

            override fun onError(p0: java.lang.Exception?) {
                _state.value = LndState.Error(p0?.message ?: "Failed to start LND")
            }
        })
    }

    private suspend fun subscribeState() {
        try {
            val request = Stateservice.SubscribeStateRequest.getDefaultInstance()
            lndStream(
                lndmobile.Lndmobile::subscribeState,
                request,
                Stateservice.SubscribeStateResponse.parser(),
            ).collect { response ->
                val newState = when (response.state) {
                    Stateservice.WalletState.NON_EXISTING -> LndState.NeedsSeed
                    Stateservice.WalletState.LOCKED -> LndState.WaitingToUnlock
                    Stateservice.WalletState.UNLOCKED -> LndState.WalletUnlocked
                    Stateservice.WalletState.RPC_ACTIVE -> LndState.RpcReady
                    Stateservice.WalletState.SERVER_ACTIVE -> LndState.Running
                    Stateservice.WalletState.WAITING_TO_START -> LndState.Starting
                    else -> LndState.Starting
                }
                _state.value = newState
            }
        } catch (e: Exception) {
            if (_state.value != LndState.Stopped) {
                _state.value = LndState.Error(e.message ?: "State subscription failed")
            }
        }
    }

    suspend fun unlockWallet(password: String) {
        _state.value = LndState.WalletUnlocked
        val request = Walletunlocker.UnlockWalletRequest.newBuilder()
            .setWalletPassword(ByteString.copyFromUtf8(password))
            .build()
        lndCall(
            lndmobile.Lndmobile::unlockWallet,
            request,
            Walletunlocker.UnlockWalletResponse.parser(),
        )
    }

    suspend fun initWallet(
        password: String,
        seed: List<String>,
        recoveryWindow: Int = 0,
        channelBackup: ByteArray? = null,
    ) {
        _state.value = LndState.WalletUnlocked
        val builder = Walletunlocker.InitWalletRequest.newBuilder()
            .setWalletPassword(ByteString.copyFromUtf8(password))
            .addAllCipherSeedMnemonic(seed)
        if (recoveryWindow > 0) {
            builder.recoveryWindow = recoveryWindow
        }
        if (channelBackup != null) {
            val multiChan = LightningOuterClass.MultiChanBackup.newBuilder()
                .setMultiChanBackup(ByteString.copyFrom(channelBackup))
                .build()
            val snapshot = LightningOuterClass.ChanBackupSnapshot.newBuilder()
                .setMultiChanBackup(multiChan)
                .build()
            builder.setChannelBackups(snapshot)
        }
        lndCall(
            lndmobile.Lndmobile::initWallet,
            builder.build(),
            Walletunlocker.InitWalletResponse.parser(),
        )
    }

    suspend fun exportChannelBackup(): ByteArray {
        val request = LightningOuterClass.ChanBackupExportRequest.getDefaultInstance()
        val response = lndCall(
            lndmobile.Lndmobile::exportAllChannelBackups,
            request,
            LightningOuterClass.ChanBackupSnapshot.parser(),
        )
        return response.multiChanBackup.multiChanBackup.toByteArray()
    }

    suspend fun genSeed(): List<String> {
        val request = Walletunlocker.GenSeedRequest.getDefaultInstance()
        val response = lndCall(
            lndmobile.Lndmobile::genSeed,
            request,
            Walletunlocker.GenSeedResponse.parser(),
        )
        return response.cipherSeedMnemonicList
    }

    /** Watch LND logs via logcat for boot progress messages. */
    private suspend fun watchLndLogs() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // -T 1 starts from the most recent line (effectively "now"), avoiding stale logs
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-s", "GoLog:I", "-v", "raw", "-T", "1"))
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    // Extract meaningful boot messages
                    val msg = when {
                        l.contains("Wallet opened") -> "  Wallet database opened..."
                        l.contains("Populating in-memory channel graph") -> "  Populating in-memory channel graph..."
                        l.contains("bbolt database") -> "Opening bbolt database..."
                        l.contains("Database(s) now open") -> "  Database(s) now open"
                        l.contains("Done catching up") -> "  Chain sync complete"
                        else -> null
                    }
                    if (msg != null) {
                        _bootLog.value = msg
                    }
                    // Stop watching once we're running
                    if (_state.value == LndState.Running) {
                        process.destroy()
                        break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** Subscribe to invoice updates — emits on every invoice state change. */
    fun subscribeInvoiceUpdates(onUpdate: () -> Unit) {
        scope.launch {
            try {
                val request = LightningOuterClass.InvoiceSubscription.getDefaultInstance()
                lndStream(
                    lndmobile.Lndmobile::subscribeInvoices,
                    request,
                    LightningOuterClass.Invoice.parser(),
                ).collect { onUpdate() }
            } catch (_: Exception) {}
        }
    }

    /** Auto-save SCB to app files whenever channels change. */
    fun startBackupSubscription() {
        scope.launch {
            try {
                val request = LightningOuterClass.ChannelBackupSubscription.getDefaultInstance()
                lndStream(
                    lndmobile.Lndmobile::subscribeChannelBackups,
                    request,
                    LightningOuterClass.ChanBackupSnapshot.parser(),
                ).collect { snapshot ->
                    val bytes = snapshot.multiChanBackup.multiChanBackup.toByteArray()
                    if (bytes.isNotEmpty()) {
                        val file = lndDir.resolve("channel.backup")
                        file.writeBytes(bytes)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        _state.value = LndState.Stopped
        try {
            val intent = android.content.Intent(context, com.carbide.wallet.LndForegroundService::class.java)
            context.stopService(intent)
        } catch (_: Exception) {}
        try {
            val request = lnrpc.LightningOuterClass.StopRequest.getDefaultInstance()
            lndmobile.Lndmobile.stopDaemon(
                request.toByteArray(),
                object : lndmobile.Callback {
                    override fun onResponse(p0: ByteArray?) {}
                    override fun onError(p0: java.lang.Exception?) {}
                },
            )
        } catch (_: Exception) {}
    }
}
