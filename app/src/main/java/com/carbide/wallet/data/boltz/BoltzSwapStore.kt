package com.carbide.wallet.data.boltz

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoltzSwapStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val reverseFile: File get() = File(context.filesDir, "boltz_swap.json")
    private val submarineFile: File get() = File(context.filesDir, "boltz_sub_swap.json")

    // ======= Reverse swap (LN → On-chain) =======

    fun save(swap: ReverseSwapState) {
        val json = JSONObject().apply {
            put("type", "reverse")
            put("id", swap.id)
            put("invoice", swap.invoice)
            put("preimage", swap.preimage)
            put("preimageHash", swap.preimageHash)
            put("claimPubkey", swap.claimPubkey)
            put("claimKeyFamily", swap.claimKeyFamily)
            put("claimKeyIndex", swap.claimKeyIndex)
            put("refundPubkey", swap.refundPubkey)
            put("onchainAddress", swap.onchainAddress)
            put("onchainAmount", swap.onchainAmount)
            put("lockupAddress", swap.lockupAddress)
            put("swapTree", swap.swapTree)
            put("timeoutBlockHeight", swap.timeoutBlockHeight)
            put("status", swap.status)
        }
        reverseFile.writeText(json.toString())
    }

    fun loadReverse(): ReverseSwapState? {
        if (!reverseFile.exists()) return null
        return try {
            val json = JSONObject(reverseFile.readText())
            ReverseSwapState(
                id = json.getString("id"),
                invoice = json.getString("invoice"),
                preimage = json.getString("preimage"),
                preimageHash = json.getString("preimageHash"),
                claimPubkey = json.getString("claimPubkey"),
                claimKeyFamily = json.getInt("claimKeyFamily"),
                claimKeyIndex = json.getInt("claimKeyIndex"),
                refundPubkey = json.getString("refundPubkey"),
                onchainAddress = json.getString("onchainAddress"),
                onchainAmount = json.getLong("onchainAmount"),
                lockupAddress = json.getString("lockupAddress"),
                swapTree = json.getString("swapTree"),
                timeoutBlockHeight = json.getInt("timeoutBlockHeight"),
                status = json.optString("status", "created"),
            )
        } catch (_: Exception) { null }
    }

    fun clearReverse() = reverseFile.delete()

    // ======= Submarine swap (On-chain → LN) =======

    fun save(swap: SubmarineSwapState) {
        val json = JSONObject().apply {
            put("type", "submarine")
            put("id", swap.id)
            put("invoice", swap.invoice)
            put("refundPubkey", swap.refundPubkey)
            put("refundKeyFamily", swap.refundKeyFamily)
            put("refundKeyIndex", swap.refundKeyIndex)
            put("claimPubkey", swap.claimPubkey)
            put("address", swap.address)
            put("expectedAmount", swap.expectedAmount)
            put("swapTree", swap.swapTree)
            put("timeoutBlockHeight", swap.timeoutBlockHeight)
            put("status", swap.status)
        }
        submarineFile.writeText(json.toString())
    }

    fun loadSubmarine(): SubmarineSwapState? {
        if (!submarineFile.exists()) return null
        return try {
            val json = JSONObject(submarineFile.readText())
            SubmarineSwapState(
                id = json.getString("id"),
                invoice = json.getString("invoice"),
                refundPubkey = json.getString("refundPubkey"),
                refundKeyFamily = json.getInt("refundKeyFamily"),
                refundKeyIndex = json.getInt("refundKeyIndex"),
                claimPubkey = json.getString("claimPubkey"),
                address = json.getString("address"),
                expectedAmount = json.getLong("expectedAmount"),
                swapTree = json.getString("swapTree"),
                timeoutBlockHeight = json.getInt("timeoutBlockHeight"),
                status = json.optString("status", "created"),
            )
        } catch (_: Exception) { null }
    }

    fun clearSubmarine() = submarineFile.delete()

    // ======= Generic =======

    fun clear() {
        reverseFile.delete()
        submarineFile.delete()
    }
}
