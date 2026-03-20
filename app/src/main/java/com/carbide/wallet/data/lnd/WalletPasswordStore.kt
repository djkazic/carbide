package com.carbide.wallet.data.lnd

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletPasswordStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "carbide_wallet_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun setPassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    fun hasPassword(): Boolean = prefs.contains(KEY_PASSWORD)

    fun setSeed(words: List<String>) {
        prefs.edit().putString(KEY_SEED, words.joinToString(" ")).apply()
    }

    fun getSeed(): List<String>? {
        val raw = prefs.getString(KEY_SEED, null) ?: return null
        return raw.split(" ").filter { it.isNotEmpty() }
    }

    private companion object {
        const val KEY_PASSWORD = "wallet_password"
        const val KEY_SEED = "wallet_seed"
    }
}
