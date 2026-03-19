package com.carbide.wallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.carbide.wallet.data.BiometricLockManager
import com.carbide.wallet.ui.navigation.CarbideNavGraph
import com.carbide.wallet.ui.screens.BiometricLockScreen
import com.carbide.wallet.ui.theme.CarbideTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var biometricLockManager: BiometricLockManager

    lateinit var nfcManager: com.carbide.wallet.data.NfcManager
    private val isLocked = mutableStateOf(false)
    private var needsAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        nfcManager = com.carbide.wallet.data.NfcManager(this)
        nfcManager.handleIntent(intent)

        if (biometricLockManager.isEnabled) {
            isLocked.value = true
            needsAuth = true
        }

        setContent {
            CarbideTheme {
                Box(Modifier.fillMaxSize()) {
                    // Always render NavGraph so LND starts regardless of lock state
                    val navController = rememberNavController()
                    CarbideNavGraph(navController = navController)

                    // Overlay lock screen on top
                    if (isLocked.value) {
                        BiometricLockScreen(onUnlock = { authenticate() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        nfcManager.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcManager.enableReader()
        if (needsAuth && biometricLockManager.isEnabled) {
            isLocked.value = true
            authenticate()
        }
        needsAuth = false
    }

    override fun onPause() {
        super.onPause()
        nfcManager.disableReader()
        nfcManager.setPushMessage(null)
        if (biometricLockManager.isEnabled) {
            isLocked.value = true
            needsAuth = true
        }
    }

    private fun authenticate() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isLocked.value = false
                needsAuth = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Stay locked — user can tap Unlock to retry
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Carbide")
            .setSubtitle("Authenticate to access your wallet")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(info)
    }
}
