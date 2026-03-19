package com.carbide.wallet.data.lnd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChainTipProvider @Inject constructor() {

    @Volatile
    private var cachedTip: Int = 0
    private var lastTipFetchMs: Long = 0

    @Volatile
    private var cachedFeeRate: Long = 2
    private var lastFeeFetchMs: Long = 0

    /** Returns the current chain tip height, cached for 60s. */
    suspend fun getTipHeight(): Int {
        val now = System.currentTimeMillis()
        if (cachedTip > 0 && now - lastTipFetchMs < 60_000) return cachedTip

        return withContext(Dispatchers.IO) {
            try {
                val height = URL("https://mempool.space/api/blocks/tip/height")
                    .readText().trim().toInt()
                cachedTip = height
                lastTipFetchMs = System.currentTimeMillis()
                height
            } catch (_: Exception) {
                cachedTip
            }
        }
    }

    /** Returns recommended fee rate (sat/vB) using mempool.space hourFee, cached for 60s. */
    suspend fun getHourFeeRate(): Long {
        val now = System.currentTimeMillis()
        if (cachedFeeRate > 0 && now - lastFeeFetchMs < 60_000) return cachedFeeRate

        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(
                    URL("https://mempool.space/api/v1/fees/recommended").readText()
                )
                val rate = json.optLong("hourFee", 2)
                cachedFeeRate = rate.coerceAtLeast(1)
                lastFeeFetchMs = System.currentTimeMillis()
                cachedFeeRate
            } catch (_: Exception) {
                cachedFeeRate
            }
        }
    }
}
