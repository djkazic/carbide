package com.carbide.wallet.data.model

data class DecodedInvoice(
    val paymentRequest: String,
    val amountSats: Long,
    val description: String,
    val destination: String,
    val expiry: Long,
    val timestamp: Long,
)
