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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.data.lnd.LnUrlPayInfo
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.SurfaceBorder
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel
import java.text.NumberFormat
import java.util.Locale

private fun isBitcoinAddress(input: String): Boolean {
    val s = input.removePrefix("bitcoin:").trim()
    return s.startsWith("bc1") || s.startsWith("1") || s.startsWith("3") ||
        s.startsWith("BC1") || s.startsWith("tb1")
}

private fun cleanInput(input: String): String =
    input.removePrefix("bitcoin:").removePrefix("BITCOIN:")
        .removePrefix("lightning:").removePrefix("LIGHTNING:")
        .trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    onBack: () -> Unit,
    prefilledInvoice: String = "",
    viewModel: WalletViewModel = hiltViewModel(),
) {
    var inputText by rememberSaveable { mutableStateOf(cleanInput(prefilledInvoice)) }
    var isProcessing by rememberSaveable { mutableStateOf(false) }
    var isSending by rememberSaveable { mutableStateOf(false) }

    // On-chain state
    var onChainAmount by rememberSaveable { mutableStateOf("") }
    var onChainFeeRate by rememberSaveable { mutableStateOf("") }
    var onChainSendAll by rememberSaveable { mutableStateOf(false) }
    var onChainConfirming by rememberSaveable { mutableStateOf(false) }

    // LNURL state
    var lnUrlAmount by rememberSaveable { mutableStateOf("") }

    val walletState by viewModel.walletState.collectAsStateWithLifecycle()

    // Lightning state
    val decodedInvoice by viewModel.decodedInvoice.collectAsStateWithLifecycle()
    val sendResult by viewModel.sendResult.collectAsStateWithLifecycle()
    val decoded = decodedInvoice?.getOrNull()
    val decodeError = decodedInvoice?.exceptionOrNull()?.message
    val sent = sendResult?.getOrNull()
    val sendError = sendResult?.exceptionOrNull()?.message

    // On-chain state
    val feeEstimateResult by viewModel.feeEstimate.collectAsStateWithLifecycle()
    val feeEstimate = feeEstimateResult?.getOrNull()
    val feeError = feeEstimateResult?.exceptionOrNull()?.message
    val sendOnChainResult by viewModel.sendOnChainResult.collectAsStateWithLifecycle()
    val txid = sendOnChainResult?.getOrNull()
    val onChainError = sendOnChainResult?.exceptionOrNull()?.message

    // LNURL state
    val lnUrlInfoResult by viewModel.lnUrlInfo.collectAsStateWithLifecycle()
    val lnUrlInfo = lnUrlInfoResult?.getOrNull()
    val lnUrlError = lnUrlInfoResult?.exceptionOrNull()?.message

    LaunchedEffect(sendResult) { if (sendResult != null) isSending = false }
    LaunchedEffect(decodedInvoice) { if (decodedInvoice != null) isProcessing = false }
    LaunchedEffect(feeEstimateResult) {
        if (feeEstimateResult != null) isProcessing = false
        if (onChainSendAll && feeEstimateResult?.isSuccess == true) {
            val fee = feeEstimateResult?.getOrNull()?.feeSats ?: 0
            onChainAmount = (walletState.onchainBalanceSats - fee).coerceAtLeast(0).toString()
        }
    }
    LaunchedEffect(sendOnChainResult) { if (sendOnChainResult != null) isSending = false }
    LaunchedEffect(lnUrlInfoResult) { if (lnUrlInfoResult != null) isProcessing = false }

    // Auto-process prefilled input
    LaunchedEffect(prefilledInvoice) {
        val cleaned = cleanInput(prefilledInvoice)
        if (cleaned.isNotBlank() && !isProcessing) {
            isProcessing = true
            if (isBitcoinAddress(cleaned)) {
                inputText = cleaned
                onChainConfirming = true
                isProcessing = false
            } else if (viewModel.lnUrlResolver.isLnAddress(cleaned)) {
                viewModel.resolveLnAddress(cleaned)
            } else {
                viewModel.decodePayReq(cleaned)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSend()
            viewModel.clearOnChainSend()
        }
    }

    // Phase logic — if prefilled and still processing, show LOADING instead of INPUT
    val phase = when {
        txid != null -> SendPhase.DONE
        sent != null -> SendPhase.DONE
        decoded != null -> SendPhase.CONFIRM_LN
        onChainConfirming && onChainAmount.isNotBlank() && feeEstimate != null -> SendPhase.CONFIRM_ONCHAIN
        onChainConfirming -> SendPhase.ONCHAIN_AMOUNT
        lnUrlInfo != null -> SendPhase.LNURL_AMOUNT
        else -> SendPhase.INPUT
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = when (phase) {
                        SendPhase.INPUT -> "Send"
                        SendPhase.ONCHAIN_AMOUNT -> "Send On-chain"
                        SendPhase.LNURL_AMOUNT -> "Send to ${lnUrlInfo?.domain ?: ""}"
                        SendPhase.CONFIRM_LN, SendPhase.CONFIRM_ONCHAIN -> "Confirm"
                        SendPhase.DONE -> "Sent"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    when (phase) {
                        SendPhase.CONFIRM_LN -> {
                            if (prefilledInvoice.isNotBlank()) onBack()
                            else viewModel.clearSend()
                        }
                        SendPhase.ONCHAIN_AMOUNT -> {
                            if (prefilledInvoice.isNotBlank()) onBack()
                            else { onChainConfirming = false; onChainAmount = "" }
                        }
                        SendPhase.CONFIRM_ONCHAIN -> viewModel.clearOnChainSend()
                        SendPhase.LNURL_AMOUNT -> {
                            if (prefilledInvoice.isNotBlank()) onBack()
                            else viewModel.clearSend()
                        }
                        else -> onBack()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Obsidian,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "send",
        ) { currentPhase ->
            when (currentPhase) {
                SendPhase.INPUT -> InputPhase(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    isProcessing = isProcessing,
                    error = decodeError ?: lnUrlError,
                    onSubmit = {
                        val cleaned = cleanInput(inputText)
                        isProcessing = true
                        if (isBitcoinAddress(cleaned)) {
                            inputText = cleaned
                            onChainConfirming = true
                            isProcessing = false
                        } else if (viewModel.lnUrlResolver.isLnAddress(cleaned)) {
                            viewModel.resolveLnAddress(cleaned)
                        } else {
                            viewModel.decodePayReq(cleaned)
                        }
                    },
                )

                SendPhase.ONCHAIN_AMOUNT -> OnChainAmountPhase(
                    address = inputText,
                    amountText = onChainAmount,
                    onAmountChange = { onChainAmount = it; onChainSendAll = false },
                    feeRateText = onChainFeeRate,
                    onFeeRateChange = { onChainFeeRate = it },
                    onchainBalanceSats = walletState.onchainBalanceSats,
                    isEstimating = isProcessing,
                    error = feeError,
                    onMax = {
                        onChainSendAll = true
                        onChainAmount = walletState.onchainBalanceSats.toString()
                        isProcessing = true
                        // Estimate with half the balance so LND has room for fees
                        viewModel.estimateFee(inputText, (walletState.onchainBalanceSats / 2).coerceAtLeast(1))
                    },
                    onEstimate = {
                        val sats = onChainAmount.toLongOrNull() ?: 0L
                        isProcessing = true
                        viewModel.estimateFee(inputText, sats)
                    },
                )

                SendPhase.LNURL_AMOUNT -> {
                    val info = lnUrlInfo ?: return@AnimatedContent
                    LnUrlAmountPhase(
                        address = inputText,
                        info = info,
                        amountText = lnUrlAmount,
                        onAmountChange = { lnUrlAmount = it },
                        isSending = isSending,
                        sendError = sendError,
                        onSend = {
                            val sats = lnUrlAmount.toLongOrNull() ?: 0L
                            isSending = true
                            viewModel.payLnUrl(info.callback, sats)
                        },
                    )
                }

                SendPhase.CONFIRM_LN -> {
                    ConfirmLnPhase(
                        amountSats = decoded?.amountSats ?: 0,
                        description = decoded?.description ?: "",
                        destination = decoded?.destination ?: "",
                        isSending = isSending,
                        sendError = sendError,
                        onConfirm = {
                            isSending = true
                            decoded?.let { viewModel.sendPayment(it.paymentRequest) }
                        },
                    )
                }

                SendPhase.CONFIRM_ONCHAIN -> {
                    val customRate = onChainFeeRate.toLongOrNull()
                    val effectiveRate = customRate ?: feeEstimate?.satPerVbyte ?: 1
                    val fee = feeEstimate?.feeSats ?: 0
                    val sats = if (onChainSendAll) {
                        (walletState.onchainBalanceSats - fee).coerceAtLeast(0)
                    } else {
                        onChainAmount.toLongOrNull() ?: 0L
                    }
                    ConfirmOnChainPhase(
                        address = inputText,
                        amountSats = sats,
                        feeSats = fee,
                        satPerVbyte = effectiveRate,
                        sendAll = onChainSendAll,
                        isSending = isSending,
                        sendError = onChainError,
                        onConfirm = {
                            isSending = true
                            viewModel.sendOnChain(inputText, sats, effectiveRate, onChainSendAll)
                        },
                    )
                }

                SendPhase.DONE -> DonePhase(
                    message = if (txid != null) "Transaction Broadcast" else "Payment Sent",
                    onBack = onBack,
                )
            }
        }
    }
}

private enum class SendPhase { INPUT, ONCHAIN_AMOUNT, LNURL_AMOUNT, CONFIRM_LN, CONFIRM_ONCHAIN, DONE }

@Composable
private fun InputPhase(
    inputText: String,
    onInputChange: (String) -> Unit,
    isProcessing: Boolean,
    error: String?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Paste a Lightning invoice or Bitcoin address",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text("lnbc1... or bc1...", color = TextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            enabled = !isProcessing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lightning,
                unfocusedBorderColor = SurfaceBorder,
                cursorColor = Lightning,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            minLines = 3,
            maxLines = 5,
        )

        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSubmit,
            enabled = inputText.isNotBlank() && !isProcessing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lightning, contentColor = Obsidian,
                disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
            ),
        ) {
            if (isProcessing) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
            } else {
                Text("Review", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun OnChainAmountPhase(
    address: String,
    amountText: String,
    onAmountChange: (String) -> Unit,
    feeRateText: String,
    onFeeRateChange: (String) -> Unit,
    onchainBalanceSats: Long,
    isEstimating: Boolean,
    error: String?,
    onMax: () -> Unit,
    onEstimate: () -> Unit,
) {
    val fmt = remember { NumberFormat.getNumberInstance(Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        DetailRow(label = "To", value = address.take(14) + "..." + address.takeLast(8))
        DetailRow(label = "Available", value = "${fmt.format(onchainBalanceSats)} sats")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { onAmountChange(it.filter { c -> c.isDigit() }) },
                label = { Text("Amount (sats)") },
                placeholder = { Text("0", color = TextTertiary) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !isEstimating,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lightning, unfocusedBorderColor = SurfaceBorder,
                    cursorColor = Lightning,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = Lightning, unfocusedLabelColor = TextSecondary,
                ),
            )
            OutlinedButton(
                onClick = onMax,
                enabled = !isEstimating && onchainBalanceSats > 0,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Max", style = MaterialTheme.typography.labelLarge)
            }
        }

        OutlinedTextField(
            value = feeRateText,
            onValueChange = { onFeeRateChange(it.filter { c -> c.isDigit() }) },
            label = { Text("Fee rate (sat/vB)") },
            placeholder = { Text("auto", color = TextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !isEstimating,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lightning, unfocusedBorderColor = SurfaceBorder,
                cursorColor = Lightning,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = Lightning, unfocusedLabelColor = TextSecondary,
            ),
        )
        Text("Leave empty for automatic fee estimation", style = MaterialTheme.typography.labelMedium, color = TextTertiary)

        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onEstimate,
            enabled = amountText.isNotBlank() && !isEstimating,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lightning, contentColor = Obsidian,
                disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
            ),
        ) {
            if (isEstimating) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
            } else {
                Text("Review", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LnUrlAmountPhase(
    address: String,
    info: LnUrlPayInfo,
    amountText: String,
    onAmountChange: (String) -> Unit,
    isSending: Boolean,
    sendError: String?,
    onSend: () -> Unit,
) {
    val fmt = NumberFormat.getNumberInstance(Locale.US)
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        DetailRow(label = "To", value = address)

        if (info.description.isNotBlank()) {
            DetailRow(label = "Description", value = info.description)
        }

        OutlinedTextField(
            value = amountText,
            onValueChange = { onAmountChange(it.filter { c -> c.isDigit() }) },
            label = { Text("Amount (sats)") },
            placeholder = { Text("${fmt.format(info.minSendableSat)} – ${fmt.format(info.maxSendableSat)}", color = TextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !isSending,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lightning, unfocusedBorderColor = SurfaceBorder,
                cursorColor = Lightning,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = Lightning, unfocusedLabelColor = TextSecondary,
            ),
        )

        Text(
            "Min: ${fmt.format(info.minSendableSat)} · Max: ${fmt.format(info.maxSendableSat)} sats",
            style = MaterialTheme.typography.labelMedium,
            color = TextTertiary,
        )

        if (sendError != null) {
            Text(sendError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.weight(1f))

        val amountSat = amountText.toLongOrNull() ?: 0
        val valid = amountSat in info.minSendableSat..info.maxSendableSat

        Button(
            onClick = onSend,
            enabled = valid && !isSending,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lightning, contentColor = Obsidian,
                disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
            ),
        ) {
            if (isSending) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
            } else {
                Text("Send ${if (valid) "${fmt.format(amountSat)} sats" else ""}", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConfirmLnPhase(
    amountSats: Long,
    description: String,
    destination: String,
    isSending: Boolean,
    sendError: String?,
    onConfirm: () -> Unit,
) {
    val formatted = NumberFormat.getNumberInstance(Locale.US).format(amountSats)
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(formatted, style = MaterialTheme.typography.displayMedium, color = Lightning, fontWeight = FontWeight.Bold)
        Text("sats", style = MaterialTheme.typography.titleMedium, color = TextTertiary)
        Spacer(modifier = Modifier.height(32.dp))

        if (description.isNotBlank()) {
            DetailRow(label = "Memo", value = description)
            Spacer(modifier = Modifier.height(12.dp))
        }
        DetailRow(label = "To", value = destination.take(12) + "..." + destination.takeLast(12))
        DetailRow(label = "Via", value = "Lightning")

        if (sendError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(sendError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.weight(1f))
        ConfirmButton(isSending = isSending, onClick = onConfirm)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConfirmOnChainPhase(
    address: String,
    amountSats: Long,
    feeSats: Long,
    satPerVbyte: Long,
    sendAll: Boolean,
    isSending: Boolean,
    sendError: String?,
    onConfirm: () -> Unit,
) {
    val formatted = NumberFormat.getNumberInstance(Locale.US).format(amountSats)
    val feeFormatted = NumberFormat.getNumberInstance(Locale.US).format(feeSats)
    val totalFormatted = NumberFormat.getNumberInstance(Locale.US).format(amountSats + feeSats)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(formatted, style = MaterialTheme.typography.displayMedium, color = Lightning, fontWeight = FontWeight.Bold)
        Text("sats", style = MaterialTheme.typography.titleMedium, color = TextTertiary)
        Spacer(modifier = Modifier.height(32.dp))

        DetailRow(label = "To", value = address.take(14) + "..." + address.takeLast(8))
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Fee rate", value = "$satPerVbyte sat/vB")
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Network fee", value = "$feeFormatted sats")
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Total", value = "$totalFormatted sats")
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Via", value = "On-chain")

        if (sendError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(sendError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.weight(1f))
        ConfirmButton(isSending = isSending, onClick = onConfirm)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConfirmButton(isSending: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isSending,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Lightning, contentColor = Obsidian,
            disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
        ),
    ) {
        if (isSending) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
        } else {
            Text("Confirm & Send", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun DonePhase(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = "Success", tint = Positive, modifier = Modifier.size(72.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(48.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Done", style = MaterialTheme.typography.labelLarge)
        }
    }
}
