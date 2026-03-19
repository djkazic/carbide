package com.carbide.wallet.ui.components

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.LightningBright
import com.carbide.wallet.ui.theme.LightningSubtle
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import java.text.NumberFormat
import java.util.Locale

@Composable
fun BalanceDisplay(
    balanceSats: Long,
    channelBalanceSats: Long,
    onchainBalanceSats: Long,
    pendingSats: Long,
    pendingLightningSats: Long,
    outgoingHtlcSats: Long,
    synced: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedBalance by animateIntAsState(
        targetValue = balanceSats.toInt(),
        animationSpec = tween(800),
        label = "balance",
    )

    val formattedBalance = NumberFormat.getNumberInstance(Locale.US).format(animatedBalance)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceCard)
            .drawBehind {
                // Subtle lightning glow at the top
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(LightningSubtle, SurfaceCard),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.6f,
                    center = Offset(size.width * 0.5f, 0f),
                )
            }
            .padding(32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (synced) "Balance" else "Syncing...",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )

            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            brush = Brush.linearGradient(
                                colors = listOf(LightningBright, Lightning),
                            ),
                            fontWeight = FontWeight.Bold,
                            fontSize = 52.sp,
                        )
                    ) {
                        append(formattedBalance)
                    }
                },
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (channelBalanceSats > 0 || onchainBalanceSats > 0) {
                val fmt = NumberFormat.getNumberInstance(Locale.US)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = "${fmt.format(channelBalanceSats)} lightning",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                    Text(
                        text = "${fmt.format(onchainBalanceSats)} on-chain",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }

            if (pendingLightningSats > 0 || outgoingHtlcSats > 0) {
                val fmt2 = NumberFormat.getNumberInstance(Locale.US)
                val parts = mutableListOf<String>()
                if (pendingLightningSats > 0) parts.add("+${fmt2.format(pendingLightningSats)} incoming")
                if (outgoingHtlcSats > 0) parts.add("-${fmt2.format(outgoingHtlcSats)} in flight")
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (pendingSats > 0) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    val formatted = NumberFormat.getNumberInstance(Locale.US).format(pendingSats)
                    Text(
                        text = "+$formatted sats pending",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }
            }
        }
    }
}
