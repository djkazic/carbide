package com.carbide.wallet.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.carbide.wallet.MainActivity
import com.carbide.wallet.data.lnd.lndCall
import com.carbide.wallet.data.lnd.lndStream
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lnrpc.LightningOuterClass as LN
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        createChannel()

        // Watch for settled invoices
        scope.launch {
            try {
                val request = LN.InvoiceSubscription.getDefaultInstance()
                lndStream(
                    lndmobile.Lndmobile::subscribeInvoices,
                    request,
                    LN.Invoice.parser(),
                ).collect { invoice ->
                    if (invoice.state == LN.Invoice.InvoiceState.SETTLED) {
                        val amount = if (invoice.amtPaidSat > 0) invoice.amtPaidSat else invoice.value
                        showNotification("+${formatSats(amount)} sats received", invoice.memo.ifBlank { "Lightning payment received" })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Invoice subscription for notifications ended: ${e.message}")
                started = false
            }
        }

        // Watch for channel events
        scope.launch {
            try {
                lndStream(
                    lndmobile.Lndmobile::subscribeChannelEvents,
                    LN.ChannelEventSubscription.getDefaultInstance(),
                    LN.ChannelEventUpdate.parser(),
                ).collect { event ->
                    when (event.type) {
                        LN.ChannelEventUpdate.UpdateType.PENDING_OPEN_CHANNEL ->
                            showNotification("Channel opening", "A new channel is pending confirmation")
                        LN.ChannelEventUpdate.UpdateType.OPEN_CHANNEL ->
                            showNotification("Channel opened", "A new channel is now active")
                        LN.ChannelEventUpdate.UpdateType.CLOSED_CHANNEL ->
                            showNotification("Channel closed", "A channel has been closed")
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Channel event subscription ended: ${e.message}")
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Payments",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Payment received notifications"
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun formatSats(amount: Long): String =
        NumberFormat.getNumberInstance(Locale.US).format(amount)

    private fun showNotification(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(
            System.currentTimeMillis().toInt(),
            notification,
        )

        Log.d(TAG, "Payment notification: $title")
    }

    companion object {
        private const val TAG = "PaymentNotifier"
        private const val CHANNEL_ID = "carbide_payments"
    }
}
