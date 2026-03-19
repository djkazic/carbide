package com.carbide.wallet.data.boltz

import android.util.Log
import com.carbide.wallet.data.lnd.lndCall
import com.google.protobuf.ByteString
import kotlinx.coroutines.delay
import lnrpc.LightningOuterClass as LN
import signrpc.SignerOuterClass as Signer
import walletrpc.Walletkit
import walletrpc.Walletkit as WK
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoltzSwapManager @Inject constructor(
    private val boltzClient: BoltzClient,
    private val swapStore: BoltzSwapStore,
) {
    suspend fun getSwapInfo(): ReverseSwapInfo = boltzClient.getReverseSwapInfo()

    suspend fun createReverseSwap(amountSat: Long): ReverseSwapState {
        // 1. Derive a claim key
        val keyResp = lndCall(
            lndmobile.Lndmobile::walletKitDeriveNextKey,
            Walletkit.KeyReq.newBuilder()
                .setKeyFamily(KEY_FAMILY_BOLTZ)
                .build(),
            Signer.KeyDescriptor.parser(),
        )
        val claimKeyLoc = keyResp.keyLoc
        val claimPubkey = keyResp.rawKeyBytes.toByteArray().toHex()

        Log.d(TAG, "Claim key: family=${claimKeyLoc.keyFamily} index=${claimKeyLoc.keyIndex} pubkey=$claimPubkey")

        // 2. Generate preimage
        val preimage = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val preimageHash = sha256(preimage)

        // 3. Get on-chain address
        val addrResp = lndCall(
            lndmobile.Lndmobile::newAddress,
            LN.NewAddressRequest.newBuilder()
                .setType(LN.AddressType.TAPROOT_PUBKEY)
                .build(),
            LN.NewAddressResponse.parser(),
        )

        // 4. Create swap on Boltz
        val swap = boltzClient.createReverseSwap(
            invoiceAmountSat = amountSat,
            claimPubkey = claimPubkey,
            preimageHash = preimageHash.toHex(),
            onchainAddress = addrResp.address,
        )

        Log.d(TAG, "Swap created: id=${swap.id} amount=${swap.onchainAmount} refundPubkey=${swap.refundPubkey}")

        val state = ReverseSwapState(
            id = swap.id,
            invoice = swap.invoice,
            preimage = preimage.toHex(),
            preimageHash = preimageHash.toHex(),
            claimPubkey = claimPubkey,
            claimKeyFamily = claimKeyLoc.keyFamily,
            claimKeyIndex = claimKeyLoc.keyIndex,
            refundPubkey = swap.refundPubkey,
            onchainAddress = addrResp.address,
            onchainAmount = swap.onchainAmount,
            lockupAddress = swap.lockupAddress,
            swapTree = swap.swapTree,
            timeoutBlockHeight = swap.timeoutBlockHeight,
            status = "created",
        )

        // Persist immediately so we can resume after app restart
        swapStore.save(state)
        return state
    }

    /** Load a previously persisted reverse swap for resumption. */
    fun loadPendingReverseSwap(): ReverseSwapState? = swapStore.loadReverse()

    /** Clear persisted reverse swap after completion or failure. */
    fun clearReverseSwap() = swapStore.clearReverse()

    /** Load a previously persisted submarine swap for resumption. */
    fun loadPendingSubmarineSwap(): SubmarineSwapState? = swapStore.loadSubmarine()

    /** Clear persisted submarine swap after completion or failure. */
    fun clearSubmarineSwap() = swapStore.clearSubmarine()

    /**
     * Send payment asynchronously — does NOT block until settlement.
     * For reverse swaps, Boltz holds the invoice until we claim,
     * so we can't use sendPaymentSync (it would deadlock).
     */
    fun payInvoiceAsync(invoice: String) {
        lndmobile.Lndmobile.sendPaymentSync(
            LN.SendRequest.newBuilder()
                .setPaymentRequest(invoice)
                .build()
                .toByteArray(),
            object : lndmobile.Callback {
                override fun onResponse(p0: ByteArray?) {
                    Log.d(TAG, "Payment completed")
                }
                override fun onError(p0: java.lang.Exception?) {
                    Log.e(TAG, "Payment error: ${p0?.message}")
                }
            },
        )
    }

    suspend fun getSwapStatus(id: String): String = boltzClient.getSwapStatus(id)

    suspend fun waitForLockup(swapId: String): String {
        for (i in 1..120) {
            val status = boltzClient.getSwapStatus(swapId)
            if (status == "transaction.mempool" || status == "transaction.confirmed") return status
            if (status.contains("error") || status.contains("failed") || status == "swap.expired") {
                throw RuntimeException("Swap failed: $status")
            }
            delay(5000)
        }
        throw RuntimeException("Timeout waiting for lockup")
    }

    /**
     * Full cooperative claim for a reverse swap:
     * 1. Build unsigned claim transaction
     * 2. Compute BIP-341 sighash
     * 3. Create MuSig2 session with taproot tweak
     * 4. Send tx + nonce + preimage to Boltz, get their nonce + partial sig
     * 5. Register Boltz nonce, sign, post our partial sig
     * 6. Boltz combines and broadcasts
     */
    suspend fun cooperativeClaim(swap: ReverseSwapState): String {
        val claimPubkeyBytes = swap.claimPubkey.hexToBytes()
        val refundPubkeyBytes = swap.refundPubkey.hexToBytes()

        // Parse swap tree to compute tapscript root
        val swapTreeJson = JSONObject(swap.swapTree)
        val claimLeaf = swapTreeJson.getJSONObject("claimLeaf")
        val refundLeaf = swapTreeJson.getJSONObject("refundLeaf")
        val claimScript = claimLeaf.getString("output").hexToBytes()
        val refundScript = refundLeaf.getString("output").hexToBytes()

        val claimLeafHash = taggedHash("TapLeaf",
            byteArrayOf(0xc0.toByte()) + varInt(claimScript.size) + claimScript)
        val refundLeafHash = taggedHash("TapLeaf",
            byteArrayOf(0xc0.toByte()) + varInt(refundScript.size) + refundScript)

        val scriptRoot = if (compareBytes(claimLeafHash, refundLeafHash) <= 0) {
            taggedHash("TapBranch", claimLeafHash + refundLeafHash)
        } else {
            taggedHash("TapBranch", refundLeafHash + claimLeafHash)
        }

        Log.d(TAG, "Tapscript root: ${scriptRoot.toHex()}")

        // Decode addresses to scriptPubKeys
        val lockupScriptPubKey = Bech32m.addressToScriptPubKey(swap.lockupAddress)
        val destScriptPubKey = Bech32m.addressToScriptPubKey(swap.onchainAddress)

        // Get lockup transaction from Boltz to find the outpoint
        val lockupTx = boltzClient.getReverseTransaction(swap.id)
        // Find which output pays to the lockup address
        val lockupVout = findOutputIndex(lockupTx.hex, lockupScriptPubKey)
        Log.d(TAG, "Lockup outpoint: ${lockupTx.id}:$lockupVout")

        val feeSat = 300L // ~1 vbyte * ~150 WU for a key-path spend at 2 sat/vB

        // Build unsigned claim tx
        val unsignedTx = ClaimTxBuilder.buildUnsigned(
            lockupTxId = lockupTx.id,
            lockupVout = lockupVout,
            lockupAmount = swap.onchainAmount,
            destScriptPubKey = destScriptPubKey,
            feeSat = feeSat,
        )

        Log.d(TAG, "Unsigned claim tx: ${unsignedTx.toHex().take(80)}...")

        // Compute BIP-341 sighash
        val sighash = ClaimTxBuilder.computeSighash(
            lockupTxId = lockupTx.id,
            lockupVout = lockupVout,
            lockupAmount = swap.onchainAmount,
            lockupScriptPubKey = lockupScriptPubKey,
            destScriptPubKey = destScriptPubKey,
            feeSat = feeSat,
        )

        Log.d(TAG, "Sighash: ${sighash.toHex()}")

        // Create MuSig2 session with taproot tweak
        val sessionResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2CreateSession,
            Signer.MuSig2SessionRequest.newBuilder()
                .setKeyLoc(Signer.KeyLocator.newBuilder()
                    .setKeyFamily(KEY_FAMILY_BOLTZ)
                    .setKeyIndex(swap.claimKeyIndex)
                    .build())
                .addAllSignerPubkeys(ByteString.copyFrom(claimPubkeyBytes))
                .addAllSignerPubkeys(ByteString.copyFrom(refundPubkeyBytes))
                .setTaprootTweak(Signer.TaprootTweakDesc.newBuilder()
                    .setScriptRoot(ByteString.copyFrom(scriptRoot))
                    .build())
                .setVersion(Signer.MuSig2Version.MUSIG2_VERSION_V100RC2)
                .build(),
            Signer.MuSig2SessionResponse.parser(),
        )

        val sessionId = sessionResp.sessionId
        val localNonces = sessionResp.localPublicNonces.toByteArray().toHex()
        Log.d(TAG, "MuSig2 session. Combined key: ${sessionResp.combinedKey.toByteArray().toHex()}")

        // Send preimage + nonce + unsigned tx to Boltz. They return their nonce + partial sig.
        val boltzResp = boltzClient.getReverseClaimDetails(
            id = swap.id,
            preimage = swap.preimage,
            pubNonce = localNonces,
            transactionHex = unsignedTx.toHex(),
        )
        Log.d(TAG, "Boltz nonce: ${boltzResp.pubNonce}")
        Log.d(TAG, "Boltz partial sig: ${boltzResp.partialSignature}")

        // Register Boltz's nonce
        lndCall(
            lndmobile.Lndmobile::signerMuSig2RegisterNonces,
            Signer.MuSig2RegisterNoncesRequest.newBuilder()
                .setSessionId(sessionId)
                .addOtherSignerPublicNonces(ByteString.copyFrom(boltzResp.pubNonce.hexToBytes()))
                .build(),
            Signer.MuSig2RegisterNoncesResponse.parser(),
        )

        // Sign the sighash
        val signResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2Sign,
            Signer.MuSig2SignRequest.newBuilder()
                .setSessionId(sessionId)
                .setMessageDigest(ByteString.copyFrom(sighash))
                .build(),
            Signer.MuSig2SignResponse.parser(),
        )
        val ourPartialSig = signResp.localPartialSignature.toByteArray().toHex()
        Log.d(TAG, "Our partial sig: $ourPartialSig")

        // Combine Boltz's partial signature with ours to get the final Schnorr signature
        val combineResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2CombineSig,
            Signer.MuSig2CombineSigRequest.newBuilder()
                .setSessionId(sessionId)
                .addOtherPartialSignatures(ByteString.copyFrom(boltzResp.partialSignature.hexToBytes()))
                .build(),
            Signer.MuSig2CombineSigResponse.parser(),
        )

        if (!combineResp.haveAllSignatures) {
            throw RuntimeException("Failed to combine MuSig2 signatures")
        }

        val finalSig = combineResp.finalSignature.toByteArray()
        Log.d(TAG, "Final Schnorr signature: ${finalSig.toHex()} (${finalSig.size} bytes)")

        // Insert the signature into the tx witness
        val signedTx = ClaimTxBuilder.insertWitness(unsignedTx, finalSig)
        Log.d(TAG, "Signed claim tx: ${signedTx.toHex().take(80)}...")

        // Broadcast via LND
        val publishResp = lndCall(
            lndmobile.Lndmobile::walletKitPublishTransaction,
            walletrpc.Walletkit.Transaction.newBuilder()
                .setTxHex(ByteString.copyFrom(signedTx))
                .build(),
            walletrpc.Walletkit.PublishResponse.parser(),
        )

        val publishError = publishResp.publishError
        if (publishError.isNotEmpty()) {
            throw RuntimeException("Broadcast failed: $publishError")
        }

        Log.d(TAG, "Claim transaction broadcast!")
        return lockupTx.id // return the claim tx id
    }

    /**
     * Find which output in a raw transaction pays to the given scriptPubKey.
     */
    private fun findOutputIndex(txHex: String, targetScript: ByteArray): Int {
        val tx = txHex.hexToBytes()
        val targetHex = targetScript.toHex()
        // Scan the raw tx for the target scriptPubKey
        val hexStr = txHex.lowercase()
        val scriptHex = targetHex.lowercase()
        // Each output has: 8 bytes amount + varint scriptLen + script
        // Find the script in the hex and determine which output it's in
        // Simple approach: search for the scriptPubKey bytes in the tx outputs
        var idx = hexStr.indexOf(scriptHex)
        if (idx < 0) throw RuntimeException("Could not find lockup output in transaction")

        // Count which output this is by counting outputs before this position
        // Parse outputs properly from the raw tx
        val buf = java.nio.ByteBuffer.wrap(tx).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.position(4) // skip version

        // Check for segwit marker
        val marker = buf.get().toInt() and 0xff
        if (marker == 0) {
            buf.get() // skip flag
        } else {
            buf.position(buf.position() - 1)
        }

        // Skip inputs
        val inCount = readVarInt(buf)
        for (i in 0 until inCount) {
            buf.position(buf.position() + 32 + 4) // prevout
            val scriptLen = readVarInt(buf)
            buf.position(buf.position() + scriptLen + 4) // script + sequence
        }

        // Parse outputs
        val outCount = readVarInt(buf)
        for (i in 0 until outCount) {
            buf.position(buf.position() + 8) // amount
            val scriptLen = readVarInt(buf)
            val script = ByteArray(scriptLen)
            buf.get(script)
            if (script.toHex() == targetHex) return i
        }

        throw RuntimeException("Could not find lockup output in transaction")
    }

    private fun readVarInt(buf: java.nio.ByteBuffer): Int {
        val first = buf.get().toInt() and 0xff
        return when {
            first < 0xfd -> first
            first == 0xfd -> buf.short.toInt() and 0xffff
            else -> throw IllegalArgumentException("varInt too large")
        }
    }

    // ============ Submarine Swap (On-chain → Lightning) ============

    suspend fun getSubmarineSwapInfo(): SubmarineSwapInfo = boltzClient.getSubmarineSwapInfo()

    suspend fun createSubmarineSwap(amountSat: Long): SubmarineSwapState {
        // 1. Derive a refund key
        val keyResp = lndCall(
            lndmobile.Lndmobile::walletKitDeriveNextKey,
            Walletkit.KeyReq.newBuilder()
                .setKeyFamily(KEY_FAMILY_BOLTZ)
                .build(),
            Signer.KeyDescriptor.parser(),
        )
        val refundPubkey = keyResp.rawKeyBytes.toByteArray().toHex()
        Log.d(TAG, "Submarine refund key: family=${keyResp.keyLoc.keyFamily} index=${keyResp.keyLoc.keyIndex} pubkey=$refundPubkey")

        // 2. Create a Lightning invoice for the amount we want to receive
        val invoiceReq = LN.Invoice.newBuilder()
            .setValue(amountSat)
            .setMemo("Boltz submarine swap")
            .build()
        val invoiceResp = lndCall(
            lndmobile.Lndmobile::addInvoice,
            invoiceReq,
            LN.AddInvoiceResponse.parser(),
        )
        val invoice = invoiceResp.paymentRequest
        Log.d(TAG, "Created invoice: ${invoice.take(30)}...")

        // 3. Create the swap on Boltz
        val swap = boltzClient.createSubmarineSwap(
            invoice = invoice,
            refundPubkey = refundPubkey,
        )
        Log.d(TAG, "Submarine swap created: id=${swap.id} address=${swap.address} expected=${swap.expectedAmount}")

        val state = SubmarineSwapState(
            id = swap.id,
            invoice = invoice,
            refundPubkey = refundPubkey,
            refundKeyFamily = keyResp.keyLoc.keyFamily,
            refundKeyIndex = keyResp.keyLoc.keyIndex,
            claimPubkey = swap.claimPubkey,
            address = swap.address,
            expectedAmount = swap.expectedAmount,
            swapTree = swap.swapTree,
            timeoutBlockHeight = swap.timeoutBlockHeight,
            status = "created",
        )

        swapStore.save(state)
        return state
    }

    /** Send on-chain funds to the Boltz lockup address. */
    suspend fun sendSubmarineLockup(address: String, amountSat: Long) {
        val feeRate = 2L // TODO: use mempool.space fee rate
        val request = LN.SendCoinsRequest.newBuilder()
            .setAddr(address)
            .setAmount(amountSat)
            .setSatPerVbyte(feeRate)
            .build()
        val response = lndCall(
            lndmobile.Lndmobile::sendCoins,
            request,
            LN.SendCoinsResponse.parser(),
        )
        Log.d(TAG, "Lockup tx sent: ${response.txid}")
    }

    /** Wait for Boltz to pay our invoice, then cooperatively sign their claim. */
    suspend fun waitAndSignSubmarineClaim(swap: SubmarineSwapState) {
        // Poll for claim.pending status
        for (i in 1..120) {
            val status = boltzClient.getSwapStatus(swap.id)
            Log.d(TAG, "Submarine swap ${swap.id} status: $status")
            if (status == "transaction.claim.pending") break
            if (status == "transaction.claimed" || status == "invoice.settled") return // already done
            if (status.contains("error") || status.contains("failed") || status == "swap.expired") {
                throw RuntimeException("Swap failed: $status")
            }
            delay(5000)
        }

        // Get Boltz's claim details
        val claimDetails = boltzClient.getSubmarineClaimDetails(swap.id)
        Log.d(TAG, "Boltz claim details: nonce=${claimDetails.pubNonce.take(20)}... txHash=${claimDetails.transactionHash}")

        // Parse swap tree for tapscript root
        val swapTreeJson = JSONObject(swap.swapTree)
        val claimLeaf = swapTreeJson.getJSONObject("claimLeaf")
        val refundLeaf = swapTreeJson.getJSONObject("refundLeaf")
        val claimScript = claimLeaf.getString("output").hexToBytes()
        val refundScript = refundLeaf.getString("output").hexToBytes()

        val claimLeafHash = taggedHash("TapLeaf",
            byteArrayOf(0xc0.toByte()) + varInt(claimScript.size) + claimScript)
        val refundLeafHash = taggedHash("TapLeaf",
            byteArrayOf(0xc0.toByte()) + varInt(refundScript.size) + refundScript)

        val scriptRoot = if (compareBytes(claimLeafHash, refundLeafHash) <= 0) {
            taggedHash("TapBranch", claimLeafHash + refundLeafHash)
        } else {
            taggedHash("TapBranch", refundLeafHash + claimLeafHash)
        }

        // Create MuSig2 session — for submarine swaps, we're the refund key holder
        // and Boltz is the claim key holder. Boltz needs our partial sig to claim.
        val refundPubkeyBytes = swap.refundPubkey.hexToBytes()
        val claimPubkeyBytes = swap.claimPubkey.hexToBytes()

        val sessionResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2CreateSession,
            Signer.MuSig2SessionRequest.newBuilder()
                .setKeyLoc(Signer.KeyLocator.newBuilder()
                    .setKeyFamily(swap.refundKeyFamily)
                    .setKeyIndex(swap.refundKeyIndex)
                    .build())
                .addAllSignerPubkeys(ByteString.copyFrom(refundPubkeyBytes))
                .addAllSignerPubkeys(ByteString.copyFrom(claimPubkeyBytes))
                .setTaprootTweak(Signer.TaprootTweakDesc.newBuilder()
                    .setScriptRoot(ByteString.copyFrom(scriptRoot))
                    .build())
                .setVersion(Signer.MuSig2Version.MUSIG2_VERSION_V100RC2)
                .build(),
            Signer.MuSig2SessionResponse.parser(),
        )

        val sessionId = sessionResp.sessionId
        val localNonces = sessionResp.localPublicNonces.toByteArray().toHex()
        Log.d(TAG, "MuSig2 session created for submarine claim")

        // Register Boltz's nonce
        lndCall(
            lndmobile.Lndmobile::signerMuSig2RegisterNonces,
            Signer.MuSig2RegisterNoncesRequest.newBuilder()
                .setSessionId(sessionId)
                .addOtherSignerPublicNonces(ByteString.copyFrom(claimDetails.pubNonce.hexToBytes()))
                .build(),
            Signer.MuSig2RegisterNoncesResponse.parser(),
        )

        // Sign the transaction hash that Boltz provided
        val signResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2Sign,
            Signer.MuSig2SignRequest.newBuilder()
                .setSessionId(sessionId)
                .setMessageDigest(ByteString.copyFrom(claimDetails.transactionHash.hexToBytes()))
                .setCleanup(true)
                .build(),
            Signer.MuSig2SignResponse.parser(),
        )
        val partialSig = signResp.localPartialSignature.toByteArray().toHex()
        Log.d(TAG, "Our partial sig for submarine claim: $partialSig")

        // Send our partial signature to Boltz
        boltzClient.postSubmarineClaimSignature(
            id = swap.id,
            pubNonce = localNonces,
            partialSignature = partialSig,
        )
        Log.d(TAG, "Submarine claim signature posted to Boltz")
    }

    /** Cooperative refund for a failed submarine swap. */
    suspend fun cooperativeRefund(swap: SubmarineSwapState): String {
        val refundPubkeyBytes = swap.refundPubkey.hexToBytes()
        val claimPubkeyBytes = swap.claimPubkey.hexToBytes()

        // Parse swap tree for tapscript root
        val swapTreeJson = JSONObject(swap.swapTree)
        val claimLeaf = swapTreeJson.getJSONObject("claimLeaf")
        val refundLeaf = swapTreeJson.getJSONObject("refundLeaf")
        val claimScript = claimLeaf.getString("output").hexToBytes()
        val refundScript = refundLeaf.getString("output").hexToBytes()

        val claimLeafHash = taggedHash("TapLeaf",
            byteArrayOf(0xc0.toByte()) + varInt(claimScript.size) + claimScript)
        val refundLeafHash = taggedHash("TapLeaf",
            byteArrayOf(0xc0.toByte()) + varInt(refundScript.size) + refundScript)

        val scriptRoot = if (compareBytes(claimLeafHash, refundLeafHash) <= 0) {
            taggedHash("TapBranch", claimLeafHash + refundLeafHash)
        } else {
            taggedHash("TapBranch", refundLeafHash + claimLeafHash)
        }

        Log.d(TAG, "Refund tapscript root: ${scriptRoot.toHex()}")

        // Get lockup address script and find the outpoint
        val lockupScriptPubKey = Bech32m.addressToScriptPubKey(swap.address)

        // Get our on-chain destination
        val addrResp = lndCall(
            lndmobile.Lndmobile::newAddress,
            LN.NewAddressRequest.newBuilder()
                .setType(LN.AddressType.TAPROOT_PUBKEY)
                .build(),
            LN.NewAddressResponse.parser(),
        )
        val destScriptPubKey = Bech32m.addressToScriptPubKey(addrResp.address)

        // Get lockup transaction from Boltz
        val lockupTx = boltzClient.getSubmarineTransaction(swap.id)
        val lockupVout = findOutputIndex(lockupTx.hex, lockupScriptPubKey)
        Log.d(TAG, "Refund lockup outpoint: ${lockupTx.id}:$lockupVout")

        val feeSat = 300L

        // Build unsigned refund tx
        val unsignedTx = ClaimTxBuilder.buildUnsigned(
            lockupTxId = lockupTx.id,
            lockupVout = lockupVout,
            lockupAmount = swap.expectedAmount,
            destScriptPubKey = destScriptPubKey,
            feeSat = feeSat,
        )

        // Compute sighash
        val sighash = ClaimTxBuilder.computeSighash(
            lockupTxId = lockupTx.id,
            lockupVout = lockupVout,
            lockupAmount = swap.expectedAmount,
            lockupScriptPubKey = lockupScriptPubKey,
            destScriptPubKey = destScriptPubKey,
            feeSat = feeSat,
        )

        Log.d(TAG, "Refund sighash: ${sighash.toHex()}")

        // Create MuSig2 session
        val sessionResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2CreateSession,
            Signer.MuSig2SessionRequest.newBuilder()
                .setKeyLoc(Signer.KeyLocator.newBuilder()
                    .setKeyFamily(KEY_FAMILY_BOLTZ)
                    .setKeyIndex(swap.refundKeyIndex)
                    .build())
                .addAllSignerPubkeys(ByteString.copyFrom(refundPubkeyBytes))
                .addAllSignerPubkeys(ByteString.copyFrom(claimPubkeyBytes))
                .setTaprootTweak(Signer.TaprootTweakDesc.newBuilder()
                    .setScriptRoot(ByteString.copyFrom(scriptRoot))
                    .build())
                .setVersion(Signer.MuSig2Version.MUSIG2_VERSION_V100RC2)
                .build(),
            Signer.MuSig2SessionResponse.parser(),
        )

        val sessionId = sessionResp.sessionId
        val localNonces = sessionResp.localPublicNonces.toByteArray().toHex()

        // Send our nonce + tx to Boltz, get their partial sig
        val boltzResp = boltzClient.postSubmarineRefund(
            id = swap.id,
            pubNonce = localNonces,
            transaction = unsignedTx.toHex(),
            index = 0,
        )
        Log.d(TAG, "Boltz refund nonce: ${boltzResp.pubNonce.take(20)}...")

        // Register Boltz's nonce
        lndCall(
            lndmobile.Lndmobile::signerMuSig2RegisterNonces,
            Signer.MuSig2RegisterNoncesRequest.newBuilder()
                .setSessionId(sessionId)
                .addOtherSignerPublicNonces(ByteString.copyFrom(boltzResp.pubNonce.hexToBytes()))
                .build(),
            Signer.MuSig2RegisterNoncesResponse.parser(),
        )

        // Sign
        val signResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2Sign,
            Signer.MuSig2SignRequest.newBuilder()
                .setSessionId(sessionId)
                .setMessageDigest(ByteString.copyFrom(sighash))
                .build(),
            Signer.MuSig2SignResponse.parser(),
        )

        // Combine signatures
        val combineResp = lndCall(
            lndmobile.Lndmobile::signerMuSig2CombineSig,
            Signer.MuSig2CombineSigRequest.newBuilder()
                .setSessionId(sessionId)
                .addOtherPartialSignatures(ByteString.copyFrom(boltzResp.partialSignature.hexToBytes()))
                .build(),
            Signer.MuSig2CombineSigResponse.parser(),
        )

        if (!combineResp.haveAllSignatures) {
            throw RuntimeException("Failed to combine refund signatures")
        }

        val finalSig = combineResp.finalSignature.toByteArray()
        val signedTx = ClaimTxBuilder.insertWitness(unsignedTx, finalSig)

        // Broadcast
        val publishResp = lndCall(
            lndmobile.Lndmobile::walletKitPublishTransaction,
            walletrpc.Walletkit.Transaction.newBuilder()
                .setTxHex(ByteString.copyFrom(signedTx))
                .build(),
            walletrpc.Walletkit.PublishResponse.parser(),
        )

        if (publishResp.publishError.isNotEmpty()) {
            throw RuntimeException("Refund broadcast failed: ${publishResp.publishError}")
        }

        Log.d(TAG, "Refund transaction broadcast!")
        return lockupTx.id
    }

    companion object {
        private const val TAG = "BoltzSwap"
        private const val KEY_FAMILY_BOLTZ = 42069

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun taggedHash(tag: String, data: ByteArray): ByteArray {
            val tagHash = sha256(tag.toByteArray())
            return sha256(tagHash + tagHash + data)
        }

        private fun varInt(n: Int): ByteArray = when {
            n < 0xfd -> byteArrayOf(n.toByte())
            n <= 0xffff -> byteArrayOf(0xfd.toByte(), (n and 0xff).toByte(), (n shr 8 and 0xff).toByte())
            else -> throw IllegalArgumentException("varInt too large")
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.hexToBytes(): ByteArray {
            val data = ByteArray(length / 2)
            for (i in data.indices) {
                data[i] = ((Character.digit(this[i * 2], 16) shl 4) +
                    Character.digit(this[i * 2 + 1], 16)).toByte()
            }
            return data
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in a.indices) {
                if (i >= b.size) return 1
                val cmp = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
                if (cmp != 0) return cmp
            }
            return a.size.compareTo(b.size)
        }
    }
}

data class SubmarineSwapState(
    val id: String,
    val invoice: String,
    val refundPubkey: String,
    val refundKeyFamily: Int,
    val refundKeyIndex: Int,
    val claimPubkey: String,
    val address: String,
    val expectedAmount: Long,
    val swapTree: String,
    val timeoutBlockHeight: Int,
    val status: String,
)

data class ReverseSwapState(
    val id: String,
    val invoice: String,
    val preimage: String,
    val preimageHash: String,
    val claimPubkey: String,
    val claimKeyFamily: Int,
    val claimKeyIndex: Int,
    val refundPubkey: String,
    val onchainAddress: String,
    val onchainAmount: Long,
    val lockupAddress: String,
    val swapTree: String,
    val timeoutBlockHeight: Int,
    val status: String,
)
