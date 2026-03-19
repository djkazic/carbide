package com.carbide.wallet.data.model

data class ChannelInfo(
    val chanId: Long,
    val channelPoint: String,
    val remotePubkey: String,
    val capacity: Long,
    val localBalance: Long,
    val remoteBalance: Long,
    val localReserveSat: Long,
    val remoteReserveSat: Long,
    val remoteDustLimitSat: Long,
    val commitFeeSat: Long,
    val feePerKw: Long,
    val localCsvDelay: Int,
    val remoteCsvDelay: Int,
    val active: Boolean,
    val private: Boolean,
    val numPendingHtlcs: Int,
    val pendingHtlcsTotalSat: Long,
    val pendingIncomingHtlcsSat: Long,
    val pendingOutgoingHtlcsSat: Long,
    val htlcs: List<HtlcInfo>,
    val totalSatoshisSent: Long,
    val totalSatoshisReceived: Long,
) {
    /**
     * Sendable: we are non-initiator, only need to maintain reserve.
     * (Confirmed by LND's bandwidth = localBalance - localReserve.)
     *
     * Receivable: the initiator (remote) must maintain reserve + commit fee +
     * additional HTLC weight fee (4x the per-HTLC output cost, covering both
     * commitment tx versions and both HTLC outcome paths).
     */
    /**
     * Compute the base commitment weight from the current commit fee,
     * then calculate the overhead with one HTLC in a single operation to avoid rounding errors.
     * overhead = ceil((baseWeight + 4 * 172) * feePerKw / 1000)
     * The 4x HTLC weight covers both commitment tx versions and both outcome paths.
     */
    private val initiatorOverhead: Long get() {
        if (feePerKw == 0L) return commitFeeSat
        // commitFeeSat = floor(baseWeight * feePerKw / 1000), so
        // commitFeeSat * 1000 ≈ baseWeight * feePerKw (within feePerKw of exact).
        // Add 4 HTLC output weights and re-divide, matching LND's rounding.
        return (commitFeeSat * 1000 + 4 * 172 * feePerKw) / 1000
    }

    val receivableSat: Long get() = (remoteBalance - remoteReserveSat - initiatorOverhead).coerceAtLeast(0)
    val sendableSat: Long get() = (localBalance - localReserveSat).coerceAtLeast(0)
}

data class PendingChannelInfo(
    val channelPoint: String,
    val remotePubkey: String,
    val capacity: Long,
    val localBalance: Long,
    val remoteBalance: Long,
    val type: PendingType,
) {
    enum class PendingType { OPENING, CLOSING, FORCE_CLOSING, WAITING_CLOSE }
}

data class HtlcInfo(
    val incoming: Boolean,
    val amount: Long,
    val hashLock: String,
    val expirationHeight: Int,
)

