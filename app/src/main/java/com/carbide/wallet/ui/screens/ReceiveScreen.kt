package com.carbide.wallet.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.ui.components.QrCode
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.SurfaceBorder
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel

private enum class ReceiveMode { LIGHTNING, ONCHAIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    var mode by rememberSaveable { mutableStateOf(ReceiveMode.LIGHTNING) }
    val channels by viewModel.channels.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshChannels() }

    val maxReceivable = channels.sumOf { it.receivableSat }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Text("Receive", style = MaterialTheme.typography.headlineMedium)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Obsidian,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        // Mode toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == ReceiveMode.LIGHTNING,
                onClick = { mode = ReceiveMode.LIGHTNING },
                label = { Text("Lightning") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Lightning.copy(alpha = 0.2f),
                    selectedLabelColor = Lightning,
                    containerColor = SurfaceCard,
                    labelColor = TextSecondary,
                ),
            )
            FilterChip(
                selected = mode == ReceiveMode.ONCHAIN,
                onClick = { mode = ReceiveMode.ONCHAIN },
                label = { Text("On-chain") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Lightning.copy(alpha = 0.2f),
                    selectedLabelColor = Lightning,
                    containerColor = SurfaceCard,
                    labelColor = TextSecondary,
                ),
            )
        }

        when (mode) {
            ReceiveMode.LIGHTNING -> LightningReceive(viewModel = viewModel, maxReceivable = maxReceivable)
            ReceiveMode.ONCHAIN -> OnChainReceive(viewModel = viewModel)
        }
    }
}

@Composable
private fun LightningReceive(viewModel: WalletViewModel, maxReceivable: Long) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var isGenerating by rememberSaveable { mutableStateOf(false) }
    var invoicePaid by rememberSaveable { mutableStateOf(false) }

    val invoiceResult by viewModel.invoiceResult.collectAsStateWithLifecycle()
    val invoicePair = invoiceResult?.getOrNull()
    val generatedInvoice = invoicePair?.first
    val invoicePaymentHash = invoicePair?.second
    val invoiceError = invoiceResult?.exceptionOrNull()?.message

    // Watch transactions for our invoice settling by payment hash
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    LaunchedEffect(transactions, invoicePaymentHash) {
        if (invoicePaymentHash != null && !invoicePaid) {
            val settled = transactions.any {
                it.id == invoicePaymentHash &&
                    it.status == com.carbide.wallet.data.model.Transaction.Status.SETTLED
            }
            if (settled) invoicePaid = true
        }
    }

    val context = LocalContext.current

    val displayPhase = when {
        invoicePaid -> "paid"
        generatedInvoice != null -> "invoice"
        else -> "form"
    }

    AnimatedContent(
        targetState = displayPhase,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ln-receive",
    ) { phase ->
        when (phase) {
        "paid" -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Paid",
                    tint = Positive,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "$amountText sats received",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = {
                        viewModel.clearInvoice()
                        amountText = ""
                        memo = ""
                        isGenerating = false
                        invoicePaid = false
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("New Invoice", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        "invoice" -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                QrCode(
                    data = (generatedInvoice ?: return@AnimatedContent).uppercase(),
                    modifier = Modifier.size(280.dp),
                )

                Text(
                    text = "$amountText sats",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Lightning,
                )

                if (memo.isNotBlank()) {
                    Text(
                        text = memo,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }

                Text(
                    text = "Waiting for payment...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )

                Text(
                    text = generatedInvoice ?: return@AnimatedContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            generatedInvoice?.let { copyToClipboard(context, "invoice", it) }
                        }
                        .padding(8.dp),
                )

                Text(
                    text = "Tap invoice to copy",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary,
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        viewModel.clearInvoice()
                        amountText = ""
                        memo = ""
                        isGenerating = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("New Invoice", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        "form" -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Create a Lightning invoice",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )

                if (maxReceivable > 0) {
                    val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
                    Text(
                        text = "Max receivable: ${fmt.format(maxReceivable)} sats",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lightning,
                    )
                } else {
                    Text(
                        text = "No inbound liquidity — open a channel to receive",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount (sats)") },
                    placeholder = { Text("0", color = TextTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isGenerating,
                    colors = outlinedFieldColors(),
                )

                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("Memo (optional)") },
                    placeholder = { Text("What's this for?", color = TextTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    enabled = !isGenerating,
                    colors = outlinedFieldColors(),
                )

                if (invoiceError != null) {
                    Text(invoiceError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        isGenerating = true
                        viewModel.createInvoice(amountText.toLongOrNull() ?: 0L, memo)
                    },
                    enabled = amountText.isNotBlank() && !isGenerating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = primaryButtonColors(),
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
                    } else {
                        Text("Generate Invoice", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        else -> {}
        }
    }
}

@Composable
private fun OnChainReceive(viewModel: WalletViewModel) {
    val addressResult by viewModel.onChainAddress.collectAsStateWithLifecycle()
    val address = addressResult?.getOrNull()
    val error = addressResult?.exceptionOrNull()?.message
    var requested by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (address != null) {
            Spacer(modifier = Modifier.height(8.dp))

            QrCode(
                data = "bitcoin:$address",
                modifier = Modifier.size(280.dp),
            )

            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { copyToClipboard(context, "address", address) }
                    .padding(8.dp),
            )

            Text(
                text = "Tap address to copy",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { viewModel.newAddress() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("New Address", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Text(
                text = "Generate a Bitcoin address to receive on-chain funds",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )

            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    requested = true
                    viewModel.newAddress()
                },
                enabled = !requested || error != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = primaryButtonColors(),
            ) {
                if (requested && error == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
                } else {
                    Text("Generate Address", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Lightning,
    unfocusedBorderColor = SurfaceBorder,
    cursorColor = Lightning,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = Lightning,
    unfocusedLabelColor = TextSecondary,
)

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Lightning,
    contentColor = Obsidian,
    disabledContainerColor = SurfaceBorder,
    disabledContentColor = TextTertiary,
)
