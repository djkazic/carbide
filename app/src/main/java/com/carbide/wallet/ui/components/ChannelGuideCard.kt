package com.carbide.wallet.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.data.model.WalletState
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.LightningSubtle
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.SurfaceBorder
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import java.text.NumberFormat
import java.util.Locale

private const val LSP_PUBKEY = "028c589131fae8c7e2103326542d568373019b50a9eb376a139a330c8545efb79a"
private const val LSP_HOST = "100.0.242.58:9735"
private const val MIN_ONCHAIN_SATS = 10_000L

@Composable
fun ChannelGuideCard(
    walletState: WalletState,
    viewModel: WalletViewModel,
    modifier: Modifier = Modifier,
) {
    val onChainAddress by viewModel.onChainAddress.collectAsStateWithLifecycle()
    val lspInfo by viewModel.lspInfo.collectAsStateWithLifecycle()
    val lspOrder by viewModel.lspOrder.collectAsStateWithLifecycle()
    val hasActiveOrder = viewModel.channelPromptStore.getActiveOrderId() != null

    // Hide if channel already exists/pending
    if (walletState.numActiveChannels > 0 || walletState.numPendingChannels > 0) return
    // Wait for at least one GetInfo response (blockHeight > 0 means we've polled),
    // unless there's an active order (always show that immediately)
    if (walletState.blockHeight == 0 && !hasActiveOrder) return

    val order = lspOrder?.getOrNull()
    val context = LocalContext.current

    val hasPayment = order != null && (order.bolt11Invoice != null || order.onchainAddress != null)

    // If there's an active order but we haven't loaded it yet (or it failed to load), show waiting
    if (hasActiveOrder && (lspOrder == null || (lspOrder?.isFailure == true && order == null))) {
        // Show minimal card while loading
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceCard)
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Lightning, strokeWidth = 2.dp)
                Text("Channel order in progress...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        return
    }

    val phase = when {
        order?.isCompleted == true -> GuidePhase.DONE
        hasPayment -> GuidePhase.PAY
        lspInfo?.isSuccess == true && walletState.balanceSats >= MIN_ONCHAIN_SATS -> GuidePhase.ORDER
        walletState.balanceSats >= MIN_ONCHAIN_SATS -> GuidePhase.CONNECT
        else -> GuidePhase.FUND
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.ElectricBolt, null, tint = Lightning, modifier = Modifier.size(20.dp))
            Text("Get Connected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.height(12.dp))

        AnimatedContent(targetState = phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "guide") { p ->
            when (p) {
                GuidePhase.FUND -> FundPhase(viewModel, onChainAddress?.getOrNull(), context)
                GuidePhase.CONNECT -> ConnectPhase(viewModel, lspInfo?.exceptionOrNull()?.message)
                GuidePhase.ORDER -> OrderPhase(viewModel, lspOrder?.exceptionOrNull()?.message)
                GuidePhase.PAY -> order?.let { PayPhase(it, viewModel, context) }
                GuidePhase.DONE -> DonePhase()
            }
        }
    }
}

private enum class GuidePhase { FUND, CONNECT, ORDER, PAY, DONE }

@Composable
private fun FundPhase(viewModel: WalletViewModel, address: String?, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "To use Lightning, you need a payment channel. Start by sending at least 10,000 sats to your on-chain wallet.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
        if (address != null) {
            Text(
                address, style = MaterialTheme.typography.bodyMedium, color = Lightning,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(LightningSubtle)
                    .clickable {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("address", address))
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    }.padding(12.dp),
            )
            Text("Tap to copy address", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
        } else {
            PrimaryButton("Show Deposit Address") { viewModel.newAddress() }
        }
    }
}

@Composable
private fun ConnectPhase(viewModel: WalletViewModel, error: String?) {
    var connecting by rememberSaveable { mutableStateOf(false) }
    val lspInfo by viewModel.lspInfo.collectAsStateWithLifecycle()
    LaunchedEffect(lspInfo) { if (lspInfo != null) connecting = false }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val formatted = NumberFormat.getNumberInstance(Locale.US).format(MIN_ONCHAIN_SATS)
        Text(
            "You have enough funds to open a Lightning channel. Connect to LSP1 to get started.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        PrimaryButton(if (connecting) null else "Connect to LSP1") {
            connecting = true
            viewModel.connectAndGetLspInfo(LSP_PUBKEY, LSP_HOST)
        }
    }
}

@Composable
private fun OrderPhase(viewModel: WalletViewModel, error: String?) {
    var ordering by rememberSaveable { mutableStateOf(false) }
    val lspOrder by viewModel.lspOrder.collectAsStateWithLifecycle()
    val lspInfo by viewModel.lspInfo.collectAsStateWithLifecycle()
    LaunchedEffect(lspOrder) { if (lspOrder != null) ordering = false }

    val info = lspInfo?.getOrNull()
    val minLsp = 1_000_000L
    val maxLsp = 5_000_000L
    val fmt = NumberFormat.getNumberInstance(Locale.US)

    var sizeText by rememberSaveable { mutableStateOf(fmt.format(minLsp)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "LSP1 connected. Choose how much inbound liquidity you want. The LSP will charge a small fee.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )

        OutlinedTextField(
            value = sizeText,
            onValueChange = { sizeText = it.filter { c -> c.isDigit() } },
            label = { Text("Channel size (sats)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !ordering,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lightning, unfocusedBorderColor = SurfaceBorder,
                cursorColor = Lightning,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = Lightning, unfocusedLabelColor = TextSecondary,
            ),
        )
        Text(
            "Min: ${fmt.format(minLsp)} · Max: ${fmt.format(maxLsp)} sats",
            style = MaterialTheme.typography.labelMedium, color = TextTertiary,
        )

        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        val size = sizeText.toLongOrNull() ?: 0
        val valid = size in minLsp..maxLsp

        PrimaryButton(if (ordering) null else if (valid) "Request Channel" else "Invalid size") {
            if (!valid) return@PrimaryButton
            ordering = true
            viewModel.createLspOrder(
                pubkey = LSP_PUBKEY,
                lspBalanceSat = size,
                clientBalanceSat = 0,
                channelExpiryBlocks = info?.maxChannelExpiryBlocks ?: 144 * 30,
            )
        }
    }
}

@Composable
private fun PayPhase(
    order: com.carbide.wallet.data.lnd.Lsps1Order,
    viewModel: WalletViewModel,
    context: Context,
) {
    // Prefer on-chain (solves bootstrapping), fall back to bolt11
    val onchainAddr = order.onchainAddress
    val invoice = order.bolt11Invoice

    val feeSat = if (onchainAddr != null) order.onchainFeeSat else order.bolt11FeeSat
    val totalSat = if (onchainAddr != null) order.onchainTotalSat else order.bolt11TotalSat
    val feeFormatted = NumberFormat.getNumberInstance(Locale.US).format(feeSat)
    val totalFormatted = NumberFormat.getNumberInstance(Locale.US).format(totalSat)

    var isSending by rememberSaveable { mutableStateOf(false) }
    val sendOnChainResult by viewModel.sendOnChainResult.collectAsStateWithLifecycle()

    LaunchedEffect(sendOnChainResult) {
        if (sendOnChainResult != null) isSending = false
    }

    // Auto-poll order status every 10s
    LaunchedEffect(order.orderId, order.orderState, order.onchainState) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            viewModel.pollOrderStatus(LSP_PUBKEY, order.orderId)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Pay to open your channel.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Channel size", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            Text("${NumberFormat.getNumberInstance(Locale.US).format(order.lspBalanceSat)} sats",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LSP fee", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            Text("$feeFormatted sats", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            Text("$totalFormatted sats", style = MaterialTheme.typography.bodyMedium, color = Lightning)
        }

        if (onchainAddr != null) {
            // On-chain payment path
            when (order.onchainState) {
                "PAID" -> {
                    Text("Payment confirmed! Channel opening...", style = MaterialTheme.typography.bodyMedium, color = Positive)
                }
                else -> {
                    val sendError = sendOnChainResult?.exceptionOrNull()?.message
                    val sendSuccess = sendOnChainResult?.getOrNull()

                    if (sendSuccess != null) {
                        Text("Payment sent! Waiting for confirmation...", style = MaterialTheme.typography.bodyMedium, color = Positive)
                    } else {
                        if (sendError != null) {
                            Text(sendError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }

                        PrimaryButton(if (isSending) null else "Pay $totalFormatted sats") {
                            isSending = true
                            viewModel.sendOnChain(onchainAddr, totalSat)
                        }

                        Text(
                            onchainAddr, style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(LightningSubtle)
                                .clickable {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("address", onchainAddr))
                                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                                }.padding(10.dp),
                        )
                        Text("Or tap address to copy and pay externally", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                    }
                }
            }
        } else if (invoice != null) {
            // Lightning payment fallback
            Text(
                invoice, style = MaterialTheme.typography.bodySmall, color = Lightning,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(LightningSubtle)
                    .clickable {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("invoice", invoice))
                        Toast.makeText(context, "Invoice copied", Toast.LENGTH_SHORT).show()
                    }.padding(12.dp),
            )
            Text("Tap to copy invoice · pay from another wallet", style = MaterialTheme.typography.labelMedium, color = TextTertiary)

            when (order.bolt11State) {
                "HOLD" -> Text("Payment received, channel opening...", style = MaterialTheme.typography.bodyMedium, color = Positive)
                "PAID" -> Text("Paid! Channel opening...", style = MaterialTheme.typography.bodyMedium, color = Positive)
            }
        }
    }
}

@Composable
private fun ResumingPhase() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Lightning, strokeWidth = 2.dp)
        Text("Checking channel order status...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun DonePhase() {
    Column {
        Text("Channel opening!", style = MaterialTheme.typography.bodyLarge, color = Positive)
        Text(
            "Your Lightning channel is being opened. It will be ready after a few confirmations.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
    }
}

@Composable
private fun PrimaryButton(label: String?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = label != null,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Lightning, contentColor = Obsidian,
            disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
        ),
    ) {
        if (label == null) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Lightning, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Working...", style = MaterialTheme.typography.labelLarge)
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
