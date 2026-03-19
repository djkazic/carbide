package com.carbide.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.data.model.ChannelInfo
import com.carbide.wallet.data.model.HtlcInfo
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingHtlcsScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val walletState by viewModel.walletState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshChannels() }

    val fmt = NumberFormat.getNumberInstance(Locale.US)
    val channelsWithHtlcs = channels.filter { it.numPendingHtlcs > 0 }
    val totalIncoming = channelsWithHtlcs.sumOf { it.pendingIncomingHtlcsSat }
    val totalOutgoing = channelsWithHtlcs.sumOf { it.pendingOutgoingHtlcsSat }
    val totalCount = channelsWithHtlcs.sumOf { it.numPendingHtlcs }
    val currentHeight = walletState.blockHeight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Pending HTLCs", style = MaterialTheme.typography.headlineMedium) },
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
            SummaryItem("Total", "$totalCount HTLCs")
            if (totalIncoming > 0) SummaryItem("Incoming", "+${fmt.format(totalIncoming)}")
            if (totalOutgoing > 0) SummaryItem("Outgoing", "-${fmt.format(totalOutgoing)}")
        }

        if (currentHeight > 0) {
            Text(
                "Current block height: ${fmt.format(currentHeight)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (channelsWithHtlcs.isEmpty()) {
                item {
                    Text(
                        "No pending HTLCs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }

            for (channel in channelsWithHtlcs) {
                item(key = "header_${channel.chanId}") {
                    Text(
                        "Peer: ${channel.remotePubkey}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                items(channel.htlcs, key = { "${channel.chanId}_${it.hashLock}" }) { htlc ->
                    HtlcCard(htlc, currentHeight, fmt)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
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
private fun HtlcCard(htlc: HtlcInfo, currentHeight: Int, fmt: NumberFormat) {
    val blocksRemaining = if (currentHeight > 0) htlc.expirationHeight - currentHeight else 0
    val hoursRemaining = if (blocksRemaining > 0) blocksRemaining * 10 / 60 else 0 // ~10 min per block

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Direction + Amount
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (htlc.incoming) "Incoming" else "Outgoing",
                style = MaterialTheme.typography.titleMedium,
                color = if (htlc.incoming) Positive else Lightning,
            )
            Text(
                "${if (htlc.incoming) "+" else "-"}${fmt.format(htlc.amount)} sats",
                style = MaterialTheme.typography.titleMedium,
                color = if (htlc.incoming) Positive else Lightning,
            )
        }

        // Timelock
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Timelock", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            Text(
                "Block ${fmt.format(htlc.expirationHeight)}" +
                    if (blocksRemaining > 0) " ($blocksRemaining blocks / ~${hoursRemaining}h)" else " (expired)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (blocksRemaining <= 0) MaterialTheme.colorScheme.error
                       else if (blocksRemaining < 144) Lightning
                       else MaterialTheme.colorScheme.onSurface,
            )
        }

        // Hash
        Column {
            Text("Payment Hash", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
            Text(
                htlc.hashLock,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
