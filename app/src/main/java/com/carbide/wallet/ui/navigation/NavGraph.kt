package com.carbide.wallet.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.carbide.wallet.data.lnd.LndState
import com.carbide.wallet.ui.screens.AuthScreen
import com.carbide.wallet.ui.screens.ChannelsScreen
import com.carbide.wallet.ui.screens.ContactsScreen
import com.carbide.wallet.ui.screens.HomeScreen
import com.carbide.wallet.ui.screens.PendingHtlcsScreen
import com.carbide.wallet.ui.screens.LoadingScreen
import com.carbide.wallet.ui.screens.ReceiveScreen
import com.carbide.wallet.ui.screens.ScanScreen
import com.carbide.wallet.ui.screens.SendScreen
import com.carbide.wallet.ui.screens.SwapScreen
import com.carbide.wallet.ui.screens.SettingsScreen
import com.carbide.wallet.ui.screens.TransactionDetailScreen
import com.carbide.wallet.ui.screens.WalletSetupScreen
import com.carbide.wallet.viewmodel.WalletViewModel

private const val LSP_PUBKEY = "028c589131fae8c7e2103326542d568373019b50a9eb376a139a330c8545efb79a"
private const val LSP_HOST = "100.0.242.58:9735"

@Composable
fun CarbideNavGraph(navController: NavHostController) {
    val viewModel: WalletViewModel = hiltViewModel()
    val lndState by viewModel.lndState.collectAsStateWithLifecycle()

    // Request notification permission on Android 13+
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ -> }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startLnd()
    }

    LaunchedEffect(lndState) {
        val currentRoute = navController.currentDestination?.route
        when (lndState) {
            is LndState.NeedsSeed -> {
                if (currentRoute == Screen.Loading.route) {
                    navController.navigate(Screen.WalletSetup.route) {
                        popUpTo(Screen.Loading.route) { inclusive = true }
                    }
                }
            }
            is LndState.WaitingToUnlock -> {
                // Auto-unlock with stored password
                viewModel.tryAutoUnlock()
            }
            is LndState.Running -> {
                viewModel.startBackupSubscription()
                viewModel.startChannelAcceptor()
                viewModel.startInvoiceSubscription()
                viewModel.startPaymentNotifications()
                viewModel.startLnadHandler()
                viewModel.startScbAutoBackup()
                viewModel.maybeRestoreScb()
                viewModel.connectToLsp()
                viewModel.resumeActiveOrder(LSP_PUBKEY, LSP_HOST)
                if (currentRoute == Screen.Loading.route ||
                    currentRoute == Screen.WalletSetup.route
                ) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            else -> {}
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Loading.route,
        enterTransition = {
            slideInVertically(tween(350)) { it / 4 } + fadeIn(tween(350))
        },
        exitTransition = {
            fadeOut(tween(200))
        },
        popEnterTransition = {
            fadeIn(tween(250))
        },
        popExitTransition = {
            slideOutVertically(tween(300)) { it / 4 } + fadeOut(tween(200))
        },
    ) {
        composable(Screen.Loading.route) {
            val bootLog by viewModel.bootLog.collectAsStateWithLifecycle()
            LoadingScreen(lndState = lndState, bootLog = bootLog)
        }
        composable(Screen.WalletSetup.route) {
            WalletSetupScreen(viewModel = viewModel)
        }
        composable(Screen.Home.route) {
            HomeScreen(
                onSendClick = { navController.navigate(Screen.Send.noInvoice()) },
                onReceiveClick = { navController.navigate(Screen.Receive.route) },
                onScanClick = { navController.navigate(Screen.Scan.route) },
                onChannelsClick = { navController.navigate(Screen.Channels.route) },
                onTxClick = { txId -> navController.navigate(Screen.TransactionDetail.withId(txId)) },
                onSwapClick = { navController.navigate(Screen.Swap.route) },
                onPendingHtlcsClick = { navController.navigate(Screen.PendingHtlcs.route) },
                onContactsClick = { navController.navigate(Screen.Contacts.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
            )
        }
        composable(
            route = Screen.Send.route,
            arguments = listOf(
                navArgument("invoice") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val prefilled = backStackEntry.arguments?.getString("invoice") ?: ""
            SendScreen(
                onBack = { navController.popBackStack() },
                prefilledInvoice = prefilled,
            )
        }
        composable(Screen.Scan.route) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onScanned = { value ->
                    navController.popBackStack()
                    val lower = value.lowercase()
                    val isAuth = when {
                        lower.contains("tag=login") -> true
                        lower.startsWith("keyauth://") -> true
                        lower.startsWith("lnurl1") -> {
                            // Decode bech32 to check if it's auth
                            try {
                                val decoded = com.carbide.wallet.data.lnd.LnurlAuth().parse(value)
                                true
                            } catch (_: Exception) { false }
                        }
                        else -> false
                    }
                    if (isAuth) {
                        navController.navigate(Screen.Auth.withUrl(value))
                    } else {
                        navController.navigate(Screen.Send.withInvoice(value))
                    }
                },
            )
        }
        composable(
            route = Screen.Auth.route,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url") ?: ""
            AuthScreen(encodedUrl = url, onBack = { navController.popBackStack() })
        }
        composable(Screen.Contacts.route) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                onPay = { address ->
                    navController.navigate(Screen.Send.withInvoice(address)) {
                        popUpTo(Screen.Home.route)
                    }
                },
            )
        }
        composable(Screen.Swap.route) {
            SwapScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.PendingHtlcs.route) {
            PendingHtlcsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Channels.route) {
            ChannelsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.TransactionDetail.route,
            arguments = listOf(navArgument("txId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val txId = backStackEntry.arguments?.getString("txId") ?: ""
            TransactionDetailScreen(txId = txId, onBack = { navController.popBackStack() })
        }
        composable(Screen.Receive.route) {
            ReceiveScreen(onBack = { navController.popBackStack() })
        }
    }
}
