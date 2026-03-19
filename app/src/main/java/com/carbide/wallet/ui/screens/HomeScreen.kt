package com.carbide.wallet.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.ui.components.ActionButton
import com.carbide.wallet.ui.components.BalanceDisplay
import com.carbide.wallet.ui.components.ChannelGuideCard
import com.carbide.wallet.ui.components.SyncProgressBar
import com.carbide.wallet.ui.components.TransactionItem
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.data.model.WalletState
import com.carbide.wallet.viewmodel.WalletViewModel

@Composable
fun HomeScreen(
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onScanClick: () -> Unit,
    onChannelsClick: () -> Unit,
    onTxClick: (String) -> Unit,
    onSwapClick: () -> Unit,
    onPendingHtlcsClick: () -> Unit,
    onContactsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val walletState by viewModel.walletState.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val chainTip by viewModel.chainTip.collectAsStateWithLifecycle()

    // Refresh channels for accurate spendable balance
    LaunchedEffect(walletState.numActiveChannels) { viewModel.refreshChannels() }
    val spendableLightning = channels.sumOf { it.sendableSat }
    val incomingHtlcBalance = channels.sumOf { it.pendingIncomingHtlcsSat }
    val outgoingHtlcBalance = channels.sumOf { it.pendingOutgoingHtlcsSat }

    // Fetch chain tip while syncing
    LaunchedEffect(walletState.syncedToChain) {
        if (!walletState.syncedToChain) {
            viewModel.fetchChainTip()
        }
    }

    // Subtle ambient animation for the background glow
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .drawBehind {
                // Ambient lightning glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Lightning.copy(alpha = glowAlpha),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.15f),
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.5f, size.height * 0.15f),
                )
            },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Carbide",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(
                                enabled = walletState.numActiveChannels > 0 || walletState.numPendingChannels > 0,
                                onClick = onChannelsClick,
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (walletState.synced) MaterialTheme.colorScheme.tertiary
                                        else Lightning
                                    ),
                            )
                            Text(
                                text = syncStatusText(walletState),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                        }
                    }
                    Row {
                        if (channels.any { it.numPendingHtlcs > 0 }) {
                            IconButton(onClick = onPendingHtlcsClick) {
                                Icon(
                                    imageVector = Icons.Rounded.HourglassTop,
                                    contentDescription = "Pending HTLCs",
                                    tint = Lightning,
                                )
                            }
                        }
                        IconButton(onClick = onContactsClick) {
                            Icon(
                                imageVector = Icons.Rounded.People,
                                contentDescription = "Contacts",
                                tint = TextSecondary,
                            )
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Settings",
                                tint = TextSecondary,
                            )
                        }
                    }
                }
            }

            // Sync progress
            item {
                SyncProgressBar(
                    walletState = walletState,
                    chainTip = chainTip,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            // Balance card
            item {
                BalanceDisplay(
                    balanceSats = spendableLightning + walletState.onchainBalanceSats,
                    channelBalanceSats = spendableLightning,
                    onchainBalanceSats = walletState.onchainBalanceSats,
                    pendingSats = walletState.pendingSats,
                    pendingLightningSats = walletState.pendingLightningSats + incomingHtlcBalance,
                    outgoingHtlcSats = outgoingHtlcBalance,
                    synced = walletState.synced,
                )
            }

            // Action buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val chainSynced = walletState.syncedToChain
                    ActionButton(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        label = "Send",
                        onClick = onSendClick,
                        isPrimary = true,
                        enabled = chainSynced,
                    )
                    ActionButton(
                        icon = Icons.AutoMirrored.Rounded.CallReceived,
                        label = "Receive",
                        onClick = onReceiveClick,
                        enabled = chainSynced,
                        isPrimary = true,
                    )
                    ActionButton(
                        icon = Icons.Rounded.QrCodeScanner,
                        label = "Scan",
                        onClick = onScanClick,
                        enabled = chainSynced,
                        isPrimary = true,
                    )
                    ActionButton(
                        icon = Icons.Rounded.SwapVert,
                        label = "Swap",
                        onClick = onSwapClick,
                        enabled = chainSynced,
                        isPrimary = true,
                    )
                }
            }

            // Channel guide
            item {
                ChannelGuideCard(
                    walletState = walletState,
                    viewModel = viewModel,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            // Transactions header
            item {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Transaction list
            if (transactions.isEmpty()) {
                item {
                    Text(
                        text = "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            } else {
                items(transactions, key = { it.id }) { tx ->
                    TransactionItem(
                        transaction = tx,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clickable { onTxClick(tx.id) },
                    )
                }
            }

            // Bottom spacing for nav bar
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

private fun syncStatusText(state: WalletState): String = when {
    !state.synced -> "Syncing"
    state.numActiveChannels > 0 && state.numPendingChannels > 0 ->
        "${state.numActiveChannels} channels · ${state.numPendingChannels} pending"
    state.numActiveChannels > 0 -> "${state.numActiveChannels} channels"
    state.numPendingChannels > 0 -> "${state.numPendingChannels} channel pending"
    else -> "No channels"
}
