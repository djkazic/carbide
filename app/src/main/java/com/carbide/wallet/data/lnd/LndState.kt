package com.carbide.wallet.data.lnd

sealed interface LndState {
    data object NotStarted : LndState
    data object Starting : LndState
    data object WaitingToUnlock : LndState
    data object NeedsSeed : LndState
    data object WalletUnlocked : LndState      // wallet decrypted, loading
    data object RpcReady : LndState             // RPC server up, waiting for server
    data object Running : LndState              // SERVER_ACTIVE — fully ready
    data class Error(val message: String) : LndState
    data object Stopped : LndState
}
