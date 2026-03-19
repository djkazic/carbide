package com.carbide.wallet.data.model

data class WalletState(
    val balanceSats: Long = 0,
    val channelBalanceSats: Long = 0,
    val onchainBalanceSats: Long = 0,
    val pendingSats: Long = 0,
    val pendingLightningSats: Long = 0,
    val syncedToChain: Boolean = false,
    val syncedToGraph: Boolean = false,
    val blockHeight: Int = 0,
    val bestHeaderTimestamp: Long = 0,
    val numActiveChannels: Int = 0,
    val numPendingChannels: Int = 0,
    val numPeers: Int = 0,
) {
    val synced: Boolean get() = syncedToChain && syncedToGraph
}
