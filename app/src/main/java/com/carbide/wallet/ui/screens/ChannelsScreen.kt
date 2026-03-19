package com.carbide.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.data.model.ChannelInfo
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.Negative
import com.carbide.wallet.ui.theme.Slate
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val pendingChannels by viewModel.pendingChannels.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshChannels()
    }

    val fmt = NumberFormat.getNumberInstance(Locale.US)
    val totalReceivable = channels.sumOf { it.receivableSat }
    val totalSendable = channels.sumOf { it.sendableSat }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Channels", style = MaterialTheme.typography.headlineMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Obsidian,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        // Summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryItem("Can send", "${fmt.format(totalSendable)} sats")
            SummaryItem("Can receive", "${fmt.format(totalReceivable)} sats")
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (channels.isEmpty() && pendingChannels.isEmpty()) {
                item {
                    Text(
                        "No channels",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }

            // Pending channels
            if (pendingChannels.isNotEmpty()) {
                item {
                    Text(
                        "Pending",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                items(pendingChannels, key = { it.channelPoint }) { pending ->
                    PendingChannelCard(pending, fmt)
                }
            }

            // Active channels
            if (channels.isNotEmpty()) {
                item {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(channels, key = { it.chanId }) { channel ->
                    ChannelCard(channel, fmt, viewModel)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ForceCloseButton(onForceClose: () -> Unit) {
    var holdProgress by remember { mutableStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            holdProgress = 0f
            val startTime = System.currentTimeMillis()
            var done = false
            while (isHolding && !done) {
                val elapsed = System.currentTimeMillis() - startTime
                holdProgress = (elapsed / 5000f).coerceAtMost(1f)
                if (elapsed >= 5000L) {
                    done = true
                    onForceClose()
                    isHolding = false
                }
                kotlinx.coroutines.delay(50)
            }
            if (!done) holdProgress = 0f
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isHolding) Negative.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isHolding = true
                            tryAwaitRelease()
                            isHolding = false
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Progress bar
            if (isHolding) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(holdProgress)
                        .fillMaxHeight()
                        .background(Negative.copy(alpha = 0.3f))
                        .align(Alignment.CenterStart),
                )
            }
            Text(
                if (isHolding) "Hold to force close... ${(holdProgress * 5).toInt()}s"
                else "Hold 5s to force close",
                style = MaterialTheme.typography.labelMedium,
                color = if (isHolding) Negative else TextTertiary,
            )
        }
    }
}

@Composable
private fun PendingChannelCard(pending: com.carbide.wallet.data.model.PendingChannelInfo, fmt: NumberFormat) {
    val statusText = when (pending.type) {
        com.carbide.wallet.data.model.PendingChannelInfo.PendingType.OPENING -> "Opening"
        com.carbide.wallet.data.model.PendingChannelInfo.PendingType.CLOSING -> "Closing"
        com.carbide.wallet.data.model.PendingChannelInfo.PendingType.FORCE_CLOSING -> "Force closing"
        com.carbide.wallet.data.model.PendingChannelInfo.PendingType.WAITING_CLOSE -> "Waiting close"
    }
    val statusColor = when (pending.type) {
        com.carbide.wallet.data.model.PendingChannelInfo.PendingType.OPENING -> Lightning
        else -> TextTertiary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor),
            )
            Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColor)
        }

        Text(
            pending.remotePubkey,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        DetailLine("Capacity", "${fmt.format(pending.capacity)} sats")
        DetailLine("Local", "${fmt.format(pending.localBalance)} sats")
        DetailLine("Remote", "${fmt.format(pending.remoteBalance)} sats")
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextTertiary)
        Text(value, style = MaterialTheme.typography.labelMedium, color = TextTertiary)
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextTertiary)
    }
}

@Composable
private fun ChannelCard(channel: ChannelInfo, fmt: NumberFormat, viewModel: WalletViewModel) {
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var isClosing by rememberSaveable { mutableStateOf(false) }
    val closeResult by viewModel.closeResult.collectAsStateWithLifecycle()

    LaunchedEffect(closeResult) {
        if (closeResult != null) isClosing = false
    }
    val totalUsable = channel.sendableSat + channel.receivableSat
    val localFraction = if (totalUsable > 0) channel.sendableSat.toFloat() / totalUsable else 0.5f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Peer + status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (channel.active) Positive else Negative),
            )
            Text(
                channel.remotePubkey,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Capacity bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Slate),
        ) {
            if (localFraction > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(localFraction)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Lightning),
                )
            }
            if (localFraction < 0.99f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f - localFraction),
                )
            }
        }

        // Labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Send", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                Text("${fmt.format(channel.sendableSat)}", style = MaterialTheme.typography.bodyMedium, color = Lightning)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Receive", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                Text("${fmt.format(channel.receivableSat)}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }

        // Details
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DetailLine("Capacity", "${fmt.format(channel.capacity)} sats")
            DetailLine("Reserves", "Local ${fmt.format(channel.localReserveSat)} · Remote ${fmt.format(channel.remoteReserveSat)}")
            DetailLine("CSV delay", "Local ${channel.localCsvDelay} · Remote ${channel.remoteCsvDelay} blocks")
            if (channel.numPendingHtlcs > 0) {
                DetailLine("Pending HTLCs", "${channel.numPendingHtlcs}")
                if (channel.pendingIncomingHtlcsSat > 0) {
                    DetailLine("  Incoming", "+${fmt.format(channel.pendingIncomingHtlcsSat)} sats")
                }
                if (channel.pendingOutgoingHtlcsSat > 0) {
                    DetailLine("  Outgoing", "-${fmt.format(channel.pendingOutgoingHtlcsSat)} sats")
                }
            }
        }

        // Close button
        if (showConfirm) {
            val closeError = closeResult?.exceptionOrNull()?.message
            if (closeError != null) {
                Text(closeError, style = MaterialTheme.typography.bodySmall, color = Negative)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showConfirm = false },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = {
                        isClosing = true
                        viewModel.closeChannel(channel.channelPoint, channel.remotePubkey)
                    },
                    enabled = !isClosing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Negative,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (isClosing) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onError, strokeWidth = 2.dp)
                    } else {
                        Text("Confirm Close", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            val hasActiveHtlcs = channel.numPendingHtlcs > 0
            OutlinedButton(
                onClick = { if (!hasActiveHtlcs) showConfirm = true },
                enabled = !hasActiveHtlcs,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (hasActiveHtlcs) "Close Channel (${channel.numPendingHtlcs} HTLCs pending)"
                    else "Close Channel",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (hasActiveHtlcs) {
                ForceCloseButton(
                    onForceClose = {
                        isClosing = true
                        viewModel.forceCloseChannel(channel.channelPoint)
                    },
                )
            }
        }
    }
}
