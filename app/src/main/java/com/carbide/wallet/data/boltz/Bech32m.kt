package com.carbide.wallet.data.boltz

/**
 * Bech32m decoder for taproot (bc1p...) addresses.
 * Returns the 32-byte witness program.
 */
object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3L

    fun decode(address: String): ByteArray {
        val lower = address.lowercase()
        val sepPos = lower.lastIndexOf('1')
        if (sepPos < 1) throw IllegalArgumentException("No separator")

        val hrp = lower.substring(0, sepPos)
        val dataStr = lower.substring(sepPos + 1)

        val data = IntArray(dataStr.length) {
            val c = CHARSET.indexOf(dataStr[it])
            if (c < 0) throw IllegalArgumentException("Invalid character: ${dataStr[it]}")
            c
        }

        if (!verifyChecksum(hrp, data)) {
            throw IllegalArgumentException("Invalid checksum")
        }

        // Remove checksum (last 6 chars)
        val values = data.copyOfRange(0, data.size - 6)

        // First value is the witness version
        val witnessVersion = values[0]
        if (witnessVersion != 1) throw IllegalArgumentException("Not a taproot address (version $witnessVersion)")

        // Convert remaining 5-bit values to 8-bit bytes
        val program = convertBits(values, 1, values.size, 5, 8, false)
        if (program.size != 32) throw IllegalArgumentException("Invalid program length: ${program.size}")

        return program
    }

    /** Convert a taproot address to its scriptPubKey: OP_1 (0x51) + PUSH32 (0x20) + 32-byte program */
    fun addressToScriptPubKey(address: String): ByteArray {
        val program = decode(address)
        return byteArrayOf(0x51, 0x20) + program
    }

    private fun convertBits(data: IntArray, fromIndex: Int, toIndex: Int, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1

        for (i in fromIndex until toIndex) {
            val value = data[i]
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad && bits > 0) {
            result.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            // non-zero padding
        }

        return result.toByteArray()
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        return polymod(hrpExpand(hrp) + data) == BECH32M_CONST
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            result[i] = hrp[i].code shr 5
            result[i + hrp.length + 1] = hrp[i].code and 31
        }
        result[hrp.length] = 0
        return result
    }

    private fun polymod(values: IntArray): Long {
        val gen = longArrayOf(0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L)
        var chk = 1L
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffffL) shl 5) xor v.toLong()
            for (i in 0..4) {
                if ((top shr i) and 1L == 1L) {
                    chk = chk xor gen[i]
                }
            }
        }
        return chk
    }
}
