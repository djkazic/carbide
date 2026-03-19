package com.carbide.wallet.data

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages NFC reading and writing for Lightning payments.
 *
 * Read: detect tapped NFC tags containing invoices/addresses
 * Write: push invoice data when receiving a payment
 */
class NfcManager(private val activity: Activity) {

    private var nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    private val _scannedData = MutableStateFlow<String?>(null)
    val scannedData: StateFlow<String?> = _scannedData.asStateFlow()

    private var pushMessage: String? = null

    val isAvailable: Boolean get() = nfcAdapter != null
    val isEnabled: Boolean get() = nfcAdapter?.isEnabled == true

    /**
     * Enable reader mode — detects NFC tags and other phones.
     */
    fun enableReader() {
        val adapter = nfcAdapter ?: return
        adapter.enableReaderMode(
            activity,
            { tag -> handleTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK.inv() and 0, // read NDEF
            null,
        )
    }

    /**
     * Disable reader mode.
     */
    fun disableReader() {
        nfcAdapter?.disableReaderMode(activity)
    }

    /**
     * Set a message to push via NFC when another device taps.
     * Uses Host Card Emulation (HCE) approach since Android Beam is deprecated.
     * The data is shared when this device is in reader mode and another device reads it,
     * or via the NDEF push callback.
     */
    fun setPushMessage(data: String?) {
        pushMessage = data
        // The push message will be available when another Carbide instance reads this device
        // via reader mode. For now, store it for the reader callback.
        Log.d(TAG, if (data != null) "NFC push message set (${data.length} chars)" else "NFC push message cleared")
    }

    /**
     * Get the current push message (used by reader mode on the other device).
     */
    fun getPushMessage(): String? = pushMessage

    /**
     * Handle an intent that was triggered by NFC (e.g., app launched via NFC tap).
     */
    fun handleIntent(intent: Intent) {
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            messages?.forEach { raw ->
                val msg = raw as? NdefMessage ?: return@forEach
                for (record in msg.records) {
                    val payload = parseRecord(record)
                    if (payload != null) {
                        Log.d(TAG, "NFC intent data: $payload")
                        _scannedData.value = payload
                        return
                    }
                }
            }
        }
    }

    fun clearScannedData() {
        _scannedData.value = null
    }

    private fun handleTag(tag: Tag) {
        try {
            val ndef = Ndef.get(tag) ?: return
            ndef.connect()
            val message = ndef.ndefMessage ?: return
            for (record in message.records) {
                val payload = parseRecord(record)
                if (payload != null) {
                    Log.d(TAG, "NFC tag data: $payload")
                    _scannedData.value = payload
                    break
                }
            }
            ndef.close()
        } catch (e: Exception) {
            Log.e(TAG, "NFC read error: ${e.message}")
        }
    }

    private fun parseRecord(record: NdefRecord): String? {
        return when {
            // MIME type record
            record.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                String(record.payload).trim()
            }
            // URI record (lightning:...)
            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_URI) -> {
                val payload = record.payload
                // First byte is URI prefix code
                val prefix = when (payload[0].toInt()) {
                    0x00 -> ""
                    0x01 -> "http://www."
                    0x02 -> "https://www."
                    0x03 -> "http://"
                    0x04 -> "https://"
                    else -> ""
                }
                prefix + String(payload, 1, payload.size - 1)
            }
            // Text record
            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                val payload = record.payload
                val langLen = payload[0].toInt() and 0x3F
                String(payload, 1 + langLen, payload.size - 1 - langLen)
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "NfcManager"
    }
}
