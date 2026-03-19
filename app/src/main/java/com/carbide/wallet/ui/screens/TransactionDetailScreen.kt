package com.carbide.wallet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.data.model.Transaction
import com.carbide.wallet.ui.theme.Ash
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Outbound
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.Negative
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    txId: String,
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val tx = transactions.find { it.id == txId }
    val context = LocalContext.current
    val fmt = NumberFormat.getNumberInstance(Locale.US)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Transaction", style = MaterialTheme.typography.headlineMedium) },
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

        if (tx == null) {
            Text(
                "Transaction not found",
                style = MaterialTheme.typography.bodyLarge,
                color = TextTertiary,
                modifier = Modifier.padding(20.dp),
            )
            return
        }

        val isReceived = tx.direction == Transaction.Direction.RECEIVED
        val isFailed = tx.status == Transaction.Status.FAILED
        val isPending = tx.status == Transaction.Status.PENDING
        val amountColor = when {
            isFailed -> Negative
            isPending -> Ash
            isReceived -> Positive
            else -> Outbound
        }
        val sign = if (isReceived) "+" else "-"
        val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")
            .withZone(ZoneId.systemDefault())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // Direction icon
            Icon(
                imageVector = if (isReceived) Icons.Rounded.CallReceived else Icons.Rounded.CallMade,
                contentDescription = null,
                tint = amountColor,
                modifier = Modifier.size(48.dp),
            )

            Spacer(Modifier.height(16.dp))

            // Amount
            Text(
                "$sign${fmt.format(tx.amountSats)}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = amountColor,
            )
            Text("sats", style = MaterialTheme.typography.titleMedium, color = TextTertiary)

            Spacer(Modifier.height(32.dp))

            // Detail rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceCard)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DetailRow("Status", when (tx.status) {
                    Transaction.Status.SETTLED -> "Settled"
                    Transaction.Status.PENDING -> "Pending"
                    Transaction.Status.FAILED -> "Failed"
                }, when (tx.status) {
                    Transaction.Status.SETTLED -> Positive
                    Transaction.Status.PENDING -> Lightning
                    Transaction.Status.FAILED -> Negative
                })

                DetailRow("Direction", if (isReceived) "Received" else "Sent")

                DetailRow("Type", when (tx.type) {
                    Transaction.Type.ONCHAIN -> "On-chain"
                    Transaction.Type.LIGHTNING -> "Lightning"
                })

                DetailRow("Date", dateFormatter.format(tx.timestamp))

                if (tx.feeSats > 0) {
                    DetailRow("Fee", "${fmt.format(tx.feeSats)} sats")
                }

                if (tx.memo.isNotBlank()) {
                    DetailRow("Memo", tx.memo)
                }

                if (tx.preimage.isNotBlank()) {
                    Column {
                        Text("Preimage", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tx.preimage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("preimage", tx.preimage))
                                    Toast.makeText(context, "Preimage copied", Toast.LENGTH_SHORT).show()
                                },
                        )
                    }
                }

                // Transaction/Payment ID — tappable to copy
                Column {
                    Text("ID", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tx.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("tx_id", tx.id))
                                Toast.makeText(context, "ID copied", Toast.LENGTH_SHORT).show()
                            },
                    )
                }
            }

            // Cancel button for pending received Lightning invoices
            if (tx.status == Transaction.Status.PENDING &&
                tx.direction == Transaction.Direction.RECEIVED &&
                tx.type == Transaction.Type.LIGHTNING
            ) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.cancelInvoice(tx.id) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Negative,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Cancel Invoice", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
