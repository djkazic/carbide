package com.carbide.wallet.data.boltz

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Builds a minimal Bitcoin claim transaction for Boltz reverse swaps
 * and computes the BIP-341 taproot sighash for key-path spending.
 */
object ClaimTxBuilder {

    /**
     * Build an unsigned claim transaction (with empty witness).
     * 1 input (lockup UTXO) → 1 output (destination).
     */
    fun buildUnsigned(
        lockupTxId: String,
        lockupVout: Int,
        lockupAmount: Long,
        destScriptPubKey: ByteArray,
        feeSat: Long,
    ): ByteArray {
        val outputAmount = lockupAmount - feeSat
        val out = ByteArrayOutputStream()

        // Version (1 for standard, matching boltz-client)
        out.writeLE32(1)
        // Segwit marker + flag
        out.write(byteArrayOf(0x00, 0x01))
        // Input count
        out.writeVarInt(1)
        // Input: prevout txid (internal byte order = reversed display order)
        out.write(lockupTxId.hexToBytes().reversedArray())
        // Input: prevout vout
        out.writeLE32(lockupVout)
        // Input: scriptSig (empty)
        out.writeVarInt(0)
        // Input: sequence (0 for cooperative, matching boltz-client)
        out.writeLE32(0)
        // Output count
        out.writeVarInt(1)
        // Output: amount
        out.writeLE64(outputAmount)
        // Output: scriptPubKey
        out.writeVarInt(destScriptPubKey.size)
        out.write(destScriptPubKey)
        // Witness: 1 item, empty (placeholder for signature)
        out.writeVarInt(1)
        out.writeVarInt(0)
        // Locktime
        out.writeLE32(0)

        return out.toByteArray()
    }

    /**
     * Compute BIP-341 sighash for a key-path spend with SIGHASH_DEFAULT.
     * This is the message digest that gets signed via MuSig2.
     *
     * Reference: BIP-341 "Common Signature Message"
     */
    fun computeSighash(
        lockupTxId: String,
        lockupVout: Int,
        lockupAmount: Long,
        lockupScriptPubKey: ByteArray,
        destScriptPubKey: ByteArray,
        feeSat: Long,
    ): ByteArray {
        val outputAmount = lockupAmount - feeSat

        // sha_prevouts = SHA256(serialization of all input outpoints)
        val prevouts = ByteArrayOutputStream()
        prevouts.write(lockupTxId.hexToBytes().reversedArray())
        prevouts.writeLE32(lockupVout)
        val shaPrevouts = sha256(prevouts.toByteArray())

        // sha_amounts = SHA256(serialization of all input amounts as int64 LE)
        val amounts = ByteArrayOutputStream()
        amounts.writeLE64(lockupAmount)
        val shaAmounts = sha256(amounts.toByteArray())

        // sha_scriptpubkeys = SHA256(serialization of all input scriptPubKeys)
        val scripts = ByteArrayOutputStream()
        scripts.writeCompactSize(lockupScriptPubKey.size)
        scripts.write(lockupScriptPubKey)
        val shaScriptpubkeys = sha256(scripts.toByteArray())

        // sha_sequences = SHA256(serialization of all input sequences)
        val sequences = ByteArrayOutputStream()
        sequences.writeLE32(0) // sequence 0 for cooperative
        val shaSequences = sha256(sequences.toByteArray())

        // sha_outputs = SHA256(serialization of all outputs)
        val outputs = ByteArrayOutputStream()
        outputs.writeLE64(outputAmount)
        outputs.writeCompactSize(destScriptPubKey.size)
        outputs.write(destScriptPubKey)
        val shaOutputs = sha256(outputs.toByteArray())

        // Build the sighash preimage
        val msg = ByteArrayOutputStream()
        msg.write(0x00) // epoch
        msg.write(0x00) // hash_type (SIGHASH_DEFAULT)
        msg.writeLE32(1) // nVersion
        msg.writeLE32(0) // nLockTime
        msg.write(shaPrevouts)
        msg.write(shaAmounts)
        msg.write(shaScriptpubkeys)
        msg.write(shaSequences)
        msg.write(shaOutputs)
        msg.write(0x00) // spend_type (key path, no annex)
        msg.writeLE32(0) // input_index

        return taggedHash("TapSighash", msg.toByteArray())
    }

    /**
     * Insert a 64-byte Schnorr signature into the witness of the claim transaction.
     */
    fun insertWitness(unsignedTx: ByteArray, signature: ByteArray): ByteArray {
        // Find the witness section: it's after the outputs, before locktime
        // For our simple tx: version(4) + marker(2) + inputCount(1) + input(41) + outputCount(1) + output(variable) + witness + locktime(4)
        // The witness is: varint(1) + varint(0) — we replace varint(0) with varint(64) + signature

        val out = ByteArrayOutputStream()
        // Copy everything up to the witness
        // Skip to find witness position: scan for the pattern
        // Easier: rebuild the tx with the witness filled in

        val buf = ByteBuffer.wrap(unsignedTx).order(ByteOrder.LITTLE_ENDIAN)

        // Version (4 bytes)
        val version = buf.int
        out.writeLE32(version)

        // Marker + flag (2 bytes)
        out.write(buf.get().toInt())
        out.write(buf.get().toInt())

        // Input count
        val inCount = readVarInt(buf)
        out.writeVarInt(inCount)

        // Copy inputs
        for (i in 0 until inCount) {
            val prevHash = ByteArray(32); buf.get(prevHash); out.write(prevHash)
            out.writeLE32(buf.int) // vout
            val scriptLen = readVarInt(buf)
            out.writeVarInt(scriptLen)
            if (scriptLen > 0) { val s = ByteArray(scriptLen); buf.get(s); out.write(s) }
            out.writeLE32(buf.int) // sequence
        }

        // Output count
        val outCount = readVarInt(buf)
        out.writeVarInt(outCount)

        // Copy outputs
        for (i in 0 until outCount) {
            out.writeLE64(buf.long) // amount
            val scriptLen = readVarInt(buf)
            out.writeVarInt(scriptLen)
            val s = ByteArray(scriptLen); buf.get(s); out.write(s)
        }

        // Skip old witness
        for (i in 0 until inCount) {
            val itemCount = readVarInt(buf)
            for (j in 0 until itemCount) {
                val itemLen = readVarInt(buf)
                if (itemLen > 0) buf.position(buf.position() + itemLen)
            }
        }

        // Write new witness: 1 item = the 64-byte Schnorr signature
        out.writeVarInt(1) // 1 witness item
        out.writeVarInt(signature.size) // 64 bytes
        out.write(signature)

        // Locktime
        out.writeLE32(buf.int)

        return out.toByteArray()
    }

    // --- Helpers ---

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray())
        return sha256(tagHash + tagHash + data)
    }

    private fun readVarInt(buf: ByteBuffer): Int {
        val first = buf.get().toInt() and 0xff
        return when {
            first < 0xfd -> first
            first == 0xfd -> buf.short.toInt() and 0xffff
            else -> throw IllegalArgumentException("varInt too large")
        }
    }

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun ByteArrayOutputStream.writeLE64(v: Long) {
        write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
    }

    private fun ByteArrayOutputStream.writeVarInt(v: Int) {
        when {
            v < 0xfd -> write(v)
            v <= 0xffff -> {
                write(0xfd)
                write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            }
            else -> throw IllegalArgumentException("varInt too large: $v")
        }
    }

    private fun ByteArrayOutputStream.writeCompactSize(v: Int) = writeVarInt(v)

    private fun String.hexToBytes(): ByteArray {
        val data = ByteArray(length / 2)
        for (i in data.indices) {
            data[i] = ((Character.digit(this[i * 2], 16) shl 4) +
                Character.digit(this[i * 2 + 1], 16)).toByte()
        }
        return data
    }
}
