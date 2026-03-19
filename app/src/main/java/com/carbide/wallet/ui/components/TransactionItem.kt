package com.carbide.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carbide.wallet.data.model.Transaction
import com.carbide.wallet.ui.theme.Ash
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.LightningSubtle
import com.carbide.wallet.ui.theme.Negative
import com.carbide.wallet.ui.theme.Outbound
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.Slate
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Composable
fun TransactionItem(
    transaction: Transaction,
    modifier: Modifier = Modifier,
) {
    val isReceived = transaction.direction == Transaction.Direction.RECEIVED
    val isPending = transaction.status == Transaction.Status.PENDING
    val isFailed = transaction.status == Transaction.Status.FAILED

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Direction icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isPending) LightningSubtle else Slate),
        ) {
            Icon(
                imageVector = if (isReceived) Icons.AutoMirrored.Rounded.CallReceived else Icons.AutoMirrored.Rounded.CallMade,
                contentDescription = if (isReceived) "Received" else "Sent",
                tint = when {
                    isFailed -> Negative
                    isPending -> Ash
                    isReceived -> Positive
                    else -> Outbound
                },
                modifier = Modifier.size(20.dp),
            )
        }

        // Memo and time
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.memo.ifEmpty {
                    if (isReceived) "Received" else "Sent"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val typeLabel = if (transaction.type == Transaction.Type.ONCHAIN) "On-chain" else "Lightning"
            val timeLabel = when {
                isFailed -> "Failed"
                isPending -> "Pending"
                else -> relativeTime(transaction.timestamp)
            }
            Text(
                text = "$typeLabel · $timeLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isFailed -> Negative
                    isPending -> Ash
                    else -> TextTertiary
                },
            )
        }

        // Amount
        Column(horizontalAlignment = Alignment.End) {
            val sign = if (isReceived) "+" else "-"
            val formatted = NumberFormat.getNumberInstance(Locale.US).format(transaction.amountSats)
            Text(
                text = "$sign$formatted",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    isFailed -> Negative
                    isPending -> Ash
                    isReceived -> Positive
                    else -> Outbound
                },
            )
            Text(
                text = "sats",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
            )
        }
    }
}

private fun relativeTime(instant: Instant): String {
    val duration = Duration.between(instant, Instant.now())
    return when {
        duration.toMinutes() < 1 -> "Just now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
        duration.toHours() < 24 -> "${duration.toHours()}h ago"
        duration.toDays() < 7 -> "${duration.toDays()}d ago"
        else -> "${duration.toDays() / 7}w ago"
    }
}
