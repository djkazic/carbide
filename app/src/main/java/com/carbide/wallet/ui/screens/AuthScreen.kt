package com.carbide.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.Obsidian
import com.carbide.wallet.ui.theme.Positive
import com.carbide.wallet.ui.theme.SurfaceBorder
import com.carbide.wallet.ui.theme.TextSecondary
import com.carbide.wallet.ui.theme.TextTertiary
import com.carbide.wallet.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    encodedUrl: String,
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
    val authResult by viewModel.authResult.collectAsStateWithLifecycle()
    var isAuthenticating by remember { mutableStateOf(false) }

    val request = remember {
        try { viewModel.lnurlAuth.parse(url) } catch (e: Exception) { null }
    }

    LaunchedEffect(authResult) {
        if (authResult != null) isAuthenticating = false
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearAuthResult() }
    }

    val result = authResult?.getOrNull()
    val error = authResult?.exceptionOrNull()?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Authenticate", style = MaterialTheme.typography.headlineMedium) },
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

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (request == null) {
                Text("Invalid LNURL-auth request", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("Back") }
            } else if (result?.success == true) {
                // Success
                Icon(Icons.Rounded.CheckCircle, "Authenticated", tint = Positive, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text("Authenticated", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(request.domain, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                Spacer(Modifier.height(48.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Done", style = MaterialTheme.typography.labelLarge) }
            } else {
                // Prompt
                Icon(Icons.Rounded.Fingerprint, "Auth", tint = Lightning, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(24.dp))
                Text(request.domain, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(
                    when (request.action) {
                        "register" -> "wants you to create an account"
                        "link" -> "wants to link your wallet"
                        "auth" -> "wants to authorize an action"
                        else -> "wants you to log in"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )

                if (error != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(48.dp))

                Button(
                    onClick = {
                        isAuthenticating = true
                        viewModel.clearAuthResult()
                        viewModel.authenticateLnurl(request)
                    },
                    enabled = !isAuthenticating,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lightning, contentColor = Obsidian,
                        disabledContainerColor = SurfaceBorder, disabledContentColor = TextTertiary,
                    ),
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Lightning, strokeWidth = 2.dp)
                    } else {
                        Text("Authenticate", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
