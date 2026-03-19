package com.carbide.wallet.ui.navigation

sealed class Screen(val route: String) {
    data object Loading : Screen("loading")
    data object WalletSetup : Screen("wallet_setup")
    data object Home : Screen("home")
    data object Send : Screen("send?invoice={invoice}") {
        fun withInvoice(invoice: String) = "send?invoice=$invoice"
        fun noInvoice() = "send"
    }
    data object Scan : Screen("scan")
    data object Channels : Screen("channels")
    data object TransactionDetail : Screen("tx/{txId}") {
        fun withId(txId: String) = "tx/${txId}"
    }
    data object PendingHtlcs : Screen("pending_htlcs")
    data object Auth : Screen("auth?url={url}") {
        fun withUrl(url: String) = "auth?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
    }
    data object Contacts : Screen("contacts")
    data object Swap : Screen("swap")
    data object Settings : Screen("settings")
    data object Receive : Screen("receive")
}
