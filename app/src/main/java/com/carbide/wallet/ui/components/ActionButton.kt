package com.carbide.wallet.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.LightningDim
import com.carbide.wallet.ui.theme.LightningSubtle
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary

@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(100),
        label = "press",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.scale(scale),
    ) {
        val bgColor = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            isPrimary -> Lightning
            else -> MaterialTheme.colorScheme.surfaceVariant
        }

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> TextTertiary
                isPrimary -> Obsidian
                else -> TextSecondary
            },
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(bgColor)
                .then(
                    if (isPrimary) {
                        Modifier.drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(LightningSubtle, Color.Transparent),
                                    center = Offset(size.width / 2, size.height / 2),
                                    radius = size.width,
                                ),
                                radius = size.width,
                            )
                        }
                    } else Modifier
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .size(60.dp),
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> TextTertiary
                isPrimary -> Lightning
                else -> TextSecondary
            },
        )
    }
}
