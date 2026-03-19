package com.carbide.wallet.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.SurfaceBorder
import com.carbide.wallet.ui.theme.SurfaceCard
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel

private enum class SetupPhase { WELCOME, SEED, CONFIRM, RESTORE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WalletSetupScreen(viewModel: WalletViewModel) {
    val seedWords by viewModel.seedWords.collectAsStateWithLifecycle()
    val setupError by viewModel.setupError.collectAsStateWithLifecycle()
    var phase by rememberSaveable { mutableStateOf(SetupPhase.WELCOME) }
    var backedUp by rememberSaveable { mutableStateOf(false) }
    var isCreating by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Show back button when not on welcome
        if (phase != SetupPhase.WELCOME) {
            TopAppBar(
                title = {
                    Text(
                        when (phase) {
                            SetupPhase.SEED -> "Recovery Phrase"
                            SetupPhase.CONFIRM -> "Confirm Backup"
                            SetupPhase.RESTORE -> "Restore Wallet"
                            else -> ""
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        phase = when (phase) {
                            SetupPhase.SEED -> SetupPhase.WELCOME
                            SetupPhase.CONFIRM -> SetupPhase.SEED
                            SetupPhase.RESTORE -> SetupPhase.WELCOME
                            else -> SetupPhase.WELCOME
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Obsidian,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }

        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "setup",
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        ) { currentPhase ->
            when (currentPhase) {
                SetupPhase.WELCOME -> WelcomePhase(
                    onCreate = {
                        viewModel.generateSeed()
                        phase = SetupPhase.SEED
                    },
                    onRestore = { phase = SetupPhase.RESTORE },
                )

                SetupPhase.SEED -> SeedPhase(
                    words = seedWords,
                    onNext = { phase = SetupPhase.CONFIRM },
                )

                SetupPhase.CONFIRM -> ConfirmPhase(
                    backedUp = backedUp,
                    onBackedUpChange = { backedUp = it },
                    isCreating = isCreating,
                    error = setupError,
                    onViewSeed = { phase = SetupPhase.SEED },
                    onCreate = {
                        isCreating = true
                        val password = java.util.UUID.randomUUID().toString()
                        viewModel.initWallet(password)
                    },
                )

                SetupPhase.RESTORE -> RestorePhase(
                    viewModel = viewModel,
                    error = setupError,
                )
            }
        }
    }
}

@Composable
private fun WelcomePhase(onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Carbide", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text("Lightning Wallet", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lightning, contentColor = Obsidian),
        ) {
            Text("Create New Wallet", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Restore from Seed", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedPhase(words: List<String>, onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Write down these 24 words in order. They are the only way to recover your wallet. Never share them with anyone.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (words.isEmpty()) {
            CircularProgressIndicator(Modifier.size(32.dp), color = Lightning, strokeWidth = 2.dp)
        } else {
            SeedGrid(words)
        }
        Spacer(Modifier.height(24.dp))
        if (words.isNotEmpty()) {
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lightning, contentColor = Obsidian),
            ) {
                Text("I've Written It Down", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConfirmPhase(
    backedUp: Boolean,
    onBackedUpChange: (Boolean) -> Unit,
    isCreating: Boolean,
    error: String?,
    onViewSeed: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Your wallet will be created on Bitcoin mainnet. Real funds. Make sure your seed phrase is stored safely offline.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = onViewSeed,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("View seed phrase again", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = backedUp, onCheckedChange = onBackedUpChange,
                colors = CheckboxDefaults.colors(checkedColor = Lightning, uncheckedColor = TextTertiary),
            )
            Text(
                "I have securely backed up my 24-word recovery phrase",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onCreate, enabled = backedUp && !isCreating,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lightning, contentColor = Obsidian,
                disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
            ),
        ) {
            if (isCreating) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
            } else {
                Text("Create Wallet", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RestorePhase(viewModel: WalletViewModel, error: String?) {
    var seedText by rememberSaveable { mutableStateOf("") }
    var scbBytes by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var scbName by rememberSaveable { mutableStateOf<String?>(null) }
    var isRestoring by rememberSaveable { mutableStateOf(false) }

    // Reset spinner when error arrives
    LaunchedEffect(error) {
        if (error != null) isRestoring = false
    }

    val context = LocalContext.current

    val scbPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    scbBytes = bytes
                    scbName = it.lastPathSegment ?: "channel.backup"
                }
            } catch (_: Exception) {}
        }
    }

    val words = seedText.trim().lowercase()
        .split("\\s+".toRegex())
        .filter { it.isNotEmpty() }
    val validWordCount = words.size == 24

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            "Enter your 24-word recovery phrase, separated by spaces.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = seedText,
            onValueChange = { seedText = it },
            placeholder = { Text("abandon ability able ... zoo", color = TextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            enabled = !isRestoring,
            minLines = 4,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lightning, unfocusedBorderColor = SurfaceBorder,
                cursorColor = Lightning,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        if (seedText.isNotBlank() && !validWordCount) {
            Text(
                "${words.size}/24 words",
                style = MaterialTheme.typography.labelMedium,
                color = if (words.size > 24) MaterialTheme.colorScheme.error else TextTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (validWordCount) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Positive, modifier = Modifier.size(16.dp))
                Text("24 words", style = MaterialTheme.typography.labelMedium, color = Positive)
            }
        }

        Spacer(Modifier.height(24.dp))

        // SCB import
        Text("Channel Backup (optional)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "If you have a Static Channel Backup (SCB) file, import it to recover your Lightning channels.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        if (scbBytes != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Positive, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(scbName ?: "channel.backup", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("${scbBytes!!.size} bytes", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                }
                OutlinedButton(
                    onClick = { scbBytes = null; scbName = null },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Remove", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            OutlinedButton(
                onClick = { scbPicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isRestoring,
            ) {
                Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Import SCB File", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                isRestoring = true
                viewModel.restoreWallet(words, scbBytes)
            },
            enabled = validWordCount && !isRestoring,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lightning, contentColor = Obsidian,
                disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
            ),
        ) {
            if (isRestoring) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
            } else {
                Text("Restore Wallet", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedGrid(words: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        words.forEachIndexed { index, word ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, SurfaceBorder, RoundedCornerShape(10.dp))
                    .background(SurfaceCard, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${index + 1}.", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                    Text(word, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}
