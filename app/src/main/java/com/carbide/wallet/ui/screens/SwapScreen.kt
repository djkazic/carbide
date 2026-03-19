package com.carbide.wallet.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.SurfaceBorder
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel
import java.text.NumberFormat
import java.util.Locale

private enum class SwapDirection { LN_TO_ONCHAIN, ONCHAIN_TO_LN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    var direction by rememberSaveable { mutableStateOf(SwapDirection.LN_TO_ONCHAIN) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Swap", style = MaterialTheme.typography.headlineMedium) },
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

        // Direction toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = direction == SwapDirection.LN_TO_ONCHAIN,
                onClick = { direction = SwapDirection.LN_TO_ONCHAIN },
                label = { Text("Lightning → On-chain") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Lightning.copy(alpha = 0.2f),
                    selectedLabelColor = Lightning,
                    containerColor = SurfaceCard,
                    labelColor = TextSecondary,
                ),
            )
            FilterChip(
                selected = direction == SwapDirection.ONCHAIN_TO_LN,
                onClick = { direction = SwapDirection.ONCHAIN_TO_LN },
                label = { Text("On-chain → Lightning") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Lightning.copy(alpha = 0.2f),
                    selectedLabelColor = Lightning,
                    containerColor = SurfaceCard,
                    labelColor = TextSecondary,
                ),
            )
        }

        when (direction) {
            SwapDirection.LN_TO_ONCHAIN -> ReverseSwapPane(onBack = onBack, viewModel = viewModel)
            SwapDirection.ONCHAIN_TO_LN -> SubmarineSwapPane(onBack = onBack, viewModel = viewModel)
        }
    }
}

// ==================== Reverse Swap (LN → On-chain) ====================

@Composable
private fun ReverseSwapPane(onBack: () -> Unit, viewModel: WalletViewModel) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var isSwapping by rememberSaveable { mutableStateOf(false) }

    val swapInfoResult by viewModel.swapInfo.collectAsStateWithLifecycle()
    val swapStateResult by viewModel.swapState.collectAsStateWithLifecycle()
    val swapStatus by viewModel.swapStatus.collectAsStateWithLifecycle()
    val walletState by viewModel.walletState.collectAsStateWithLifecycle()

    val swapInfo = swapInfoResult?.getOrNull()
    val infoError = swapInfoResult?.exceptionOrNull()?.message
    val swap = swapStateResult?.getOrNull()
    val swapError = swapStateResult?.exceptionOrNull()?.message

    LaunchedEffect(Unit) { viewModel.fetchSwapInfo(); viewModel.resumeSwap() }
    LaunchedEffect(swapStateResult) { if (swapStateResult != null) isSwapping = false }
    DisposableEffect(Unit) { onDispose { viewModel.clearSwapState() } }

    val fmt = NumberFormat.getNumberInstance(Locale.US)
    val done = swapStatus?.contains("claimed") == true || swapStatus?.contains("settled") == true

    if (done) {
        DonePane("Swap Complete", swap?.onchainAmount?.let { "${fmt.format(it)} sats sent to your on-chain wallet" } ?: "", onBack)
    } else if (swapError != null) {
        ErrorPane(swapError) { viewModel.clearSwapState(); isSwapping = false }
    } else if (swap != null) {
        ProcessingPane(swapStatus ?: "Processing...", "You'll receive ${fmt.format(swap.onchainAmount)} sats on-chain")
    } else {
        AmountPane(
            description = "Move Lightning balance to on-chain via Boltz atomic swap.",
            available = "${fmt.format(walletState.channelBalanceSats)} sats (Lightning)",
            minMax = swapInfo?.let { "Min: ${fmt.format(it.minAmount)} · Max: ${fmt.format(it.maxAmount)} sats" },
            amountText = amountText,
            onAmountChange = { amountText = it },
            feePreview = swapInfo?.let { info ->
                val amt = amountText.toLongOrNull() ?: 0
                if (amt > 0) Triple("${fmt.format(amt)} sats", "${fmt.format(info.totalFeeSat(amt))} sats", "${fmt.format(info.onchainAmountSat(amt))} sats")
                else null
            },
            sendLabel = "Send (Lightning)",
            receiveLabel = "Receive (On-chain)",
            isValid = swapInfo?.let { (amountText.toLongOrNull() ?: 0) in it.minAmount..it.maxAmount } ?: false,
            isSwapping = isSwapping,
            error = infoError,
            onSwap = {
                isSwapping = true
                viewModel.startReverseSwap(amountText.toLongOrNull() ?: 0)
            },
        )
    }
}

// ==================== Submarine Swap (On-chain → Lightning) ====================

@Composable
private fun SubmarineSwapPane(onBack: () -> Unit, viewModel: WalletViewModel) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var isSwapping by rememberSaveable { mutableStateOf(false) }

    val subSwapInfoResult by viewModel.subSwapInfo.collectAsStateWithLifecycle()
    val subSwapStateResult by viewModel.subSwapState.collectAsStateWithLifecycle()
    val subSwapStatus by viewModel.subSwapStatus.collectAsStateWithLifecycle()
    val walletState by viewModel.walletState.collectAsStateWithLifecycle()

    val swapInfo = subSwapInfoResult?.getOrNull()
    val infoError = subSwapInfoResult?.exceptionOrNull()?.message
    val swap = subSwapStateResult?.getOrNull()
    val swapError = subSwapStateResult?.exceptionOrNull()?.message

    LaunchedEffect(Unit) { viewModel.fetchSubSwapInfo(); viewModel.resumeSubmarineSwap() }
    LaunchedEffect(subSwapStateResult) { if (subSwapStateResult != null) isSwapping = false }
    DisposableEffect(Unit) { onDispose { viewModel.clearSubSwapState() } }

    val fmt = NumberFormat.getNumberInstance(Locale.US)
    val done = subSwapStatus?.contains("claimed") == true || subSwapStatus?.contains("settled") == true
    val refunded = subSwapStatus == "refunded"

    if (refunded) {
        DonePane("Swap Refunded", "On-chain funds returned to your wallet", onBack)
    } else if (done) {
        DonePane("Swap Complete", "Lightning balance increased", onBack)
    } else if (swapError != null) {
        ErrorPane(swapError) { viewModel.clearSubSwapState(); isSwapping = false }
    } else if (swap != null) {
        ProcessingPane(
            when (subSwapStatus) {
                null, "Creating swap..." -> "Creating swap..."
                "Sending on-chain funds..." -> "Sending ${fmt.format(swap.expectedAmount)} sats on-chain..."
                "Waiting for invoice payment..." -> "Waiting for Boltz to pay invoice..."
                "transaction.claim.pending" -> "Signing cooperative claim..."
                else -> subSwapStatus ?: "Processing..."
            },
            "Boltz will pay your Lightning invoice",
        )
    } else {
        AmountPane(
            description = "Move on-chain balance to Lightning via Boltz atomic swap.",
            available = "${fmt.format(walletState.onchainBalanceSats)} sats (On-chain)",
            minMax = swapInfo?.let { "Min: ${fmt.format(it.minAmount)} · Max: ${fmt.format(it.maxAmount)} sats" },
            amountText = amountText,
            onAmountChange = { amountText = it },
            feePreview = swapInfo?.let { info ->
                val amt = amountText.toLongOrNull() ?: 0
                if (amt > 0) Triple("${fmt.format(amt)} sats", "${fmt.format(info.totalFeeSat(amt))} sats", "${fmt.format(amt - info.totalFeeSat(amt))} sats")
                else null
            },
            sendLabel = "Send (On-chain)",
            receiveLabel = "Receive (Lightning)",
            isValid = swapInfo?.let { (amountText.toLongOrNull() ?: 0) in it.minAmount..it.maxAmount } ?: false,
            isSwapping = isSwapping,
            error = infoError,
            onSwap = {
                isSwapping = true
                viewModel.startSubmarineSwap(amountText.toLongOrNull() ?: 0)
            },
        )
    }
}

// ==================== Shared UI Components ====================

@Composable
private fun AmountPane(
    description: String,
    available: String,
    minMax: String?,
    amountText: String,
    onAmountChange: (String) -> Unit,
    feePreview: Triple<String, String, String>?, // send, fee, receive
    sendLabel: String,
    receiveLabel: String,
    isValid: Boolean,
    isSwapping: Boolean,
    error: String?,
    onSwap: () -> Unit,
) {
    var showWarning by rememberSaveable { mutableStateOf(false) }
    val fmt = NumberFormat.getNumberInstance(Locale.US)

    if (showWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWarning = false },
            containerColor = SurfaceCard,
            title = { Text("Important") },
            text = {
                Text(
                    "Swap data is stored on this device. Do not uninstall the app or clear app data while a swap is in progress — this could result in loss of funds.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWarning = false
                        onSwap()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lightning, contentColor = Obsidian),
                ) { Text("I understand, proceed") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showWarning = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (minMax != null) Text(minMax, style = MaterialTheme.typography.labelMedium, color = TextTertiary)
        Text(available, style = MaterialTheme.typography.bodyMedium, color = Lightning)

        OutlinedTextField(
            value = amountText,
            onValueChange = { onAmountChange(it.filter { c -> c.isDigit() }) },
            label = { Text("Amount (sats)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lightning, unfocusedBorderColor = SurfaceBorder,
                cursorColor = Lightning,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = Lightning, unfocusedLabelColor = TextSecondary,
            ),
        )

        if (feePreview != null) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeeRow(sendLabel, feePreview.first)
                FeeRow("Swap fee", feePreview.second)
                FeeRow(receiveLabel, feePreview.third, highlight = true)
            }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { showWarning = true },
            enabled = isValid && !isSwapping,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lightning, contentColor = Obsidian,
                disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
            ),
        ) {
            if (isSwapping) CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
            else Text("Start Swap", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeeRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) Lightning else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ProcessingPane(status: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(48.dp), color = Lightning, strokeWidth = 3.dp)
        Spacer(Modifier.height(24.dp))
        Text("Swap in progress", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
    }
}

@Composable
private fun DonePane(title: String, subtitle: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.CheckCircle, "Success", tint = Positive, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        }
        Spacer(Modifier.height(48.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Done", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ErrorPane(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Swap Failed", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        Spacer(Modifier.height(48.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Try Again", style = MaterialTheme.typography.labelLarge)
        }
    }
}
