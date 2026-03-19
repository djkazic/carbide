package com.carbide.wallet.data.lnd

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelPromptStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("carbide_channel_prompt", Context.MODE_PRIVATE)

    fun wasDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    fun dismiss() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    fun getActiveOrderId(): String? = prefs.getString(KEY_ORDER_ID, null)

    fun setActiveOrderId(orderId: String?) {
        prefs.edit().apply {
            if (orderId != null) putString(KEY_ORDER_ID, orderId)
            else remove(KEY_ORDER_ID)
        }.apply()
    }

    private companion object {
        const val KEY_DISMISSED = "channel_prompt_dismissed"
        const val KEY_ORDER_ID = "active_lsp_order_id"
    }
}
