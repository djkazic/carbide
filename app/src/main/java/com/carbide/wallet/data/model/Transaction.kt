package com.carbide.wallet.data.model

import java.time.Instant

data class Transaction(
    val id: String,
    val amountSats: Long,
    val direction: Direction,
    val status: Status,
    val type: Type,
    val memo: String,
    val timestamp: Instant,
    val feeSats: Long = 0,
    val preimage: String = "",
    val paymentRequest: String = "",
) {
    enum class Direction { SENT, RECEIVED }
    enum class Status { PENDING, SETTLED, FAILED }
    enum class Type { ONCHAIN, LIGHTNING }
}
