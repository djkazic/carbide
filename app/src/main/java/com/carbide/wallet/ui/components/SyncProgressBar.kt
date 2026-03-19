package com.carbide.wallet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.carbide.wallet.data.model.WalletState
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.LightningDim
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.Slate
import com.carbide.wallet.ui.theme.TextTertiary
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SyncProgressBar(
    walletState: WalletState,
    chainTip: Int,
    modifier: Modifier = Modifier,
) {
    // Only show during initial sync — not for brief graph re-sync flickers
    // Once fully synced, remember it and don't show again
    var hasBeenSynced by remember { mutableStateOf(false) }
    if (walletState.synced) hasBeenSynced = true
    val visible = !walletState.synced && walletState.blockHeight > 0 && !hasBeenSynced

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val hasChainProgress = !walletState.syncedToChain && chainTip > 0 && walletState.blockHeight > 0
        val chainProgress = if (hasChainProgress) {
            (walletState.blockHeight.toFloat() / chainTip.toFloat()).coerceIn(0f, 1f)
        } else 0f

        // Only determinate during chain sync when we have a tip to compare against
        val determinate = hasChainProgress
        val targetProgress = when {
            hasChainProgress -> chainProgress
            else -> 0f
        }

        val animatedProgress by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(1000, easing = LinearEasing),
            label = "sync-progress",
        )

        // Indeterminate shimmer for when we don't have a chain tip yet or during graph sync
        val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
        val shimmerOffset by shimmerTransition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        )

        val fmt = NumberFormat.getNumberInstance(Locale.US)
        val label: String
        val barColor: Brush

        if (!walletState.syncedToChain) {
            barColor = Brush.horizontalGradient(listOf(LightningDim, Lightning))
            label = if (hasChainProgress) {
                "Syncing chain · ${fmt.format(walletState.blockHeight)} / ${fmt.format(chainTip)}"
            } else {
                "Syncing chain · block ${fmt.format(walletState.blockHeight)}"
            }
        } else {
            barColor = Brush.horizontalGradient(listOf(Lightning, Positive))
            label = "Syncing graph · block ${fmt.format(walletState.blockHeight)}"
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                Spacer(Modifier.weight(1f))
                if (determinate) {
                    Text(
                        "${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Slate)
                    .clipToBounds(),
            ) {
                if (determinate) {
                    // Determinate fill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(barColor),
                    )
                } else {
                    // Indeterminate shimmer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .fillMaxHeight()
                            .offset(x = (shimmerOffset * 300).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(barColor),
                    )
                }
            }
        }
    }
}
