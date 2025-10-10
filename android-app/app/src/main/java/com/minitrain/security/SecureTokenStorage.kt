package com.minitrain.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val PREFERENCES_NAME = "mt_oauth_tokens"
private const val KEY_TOKENS = "tokens"

/**
 * Persist OAuth2 tokens using Android's encrypted shared preferences.
 */
class SecureTokenStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    suspend fun write(tokens: StoredTokens) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("accessToken", tokens.accessToken)
            put("refreshToken", tokens.refreshToken)
            put("expiresAt", tokens.expiresAt.epochSecond)
        }
        prefs.edit().putString(KEY_TOKENS, payload.toString()).apply()
    }

    suspend fun read(): StoredTokens? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return@withContext null
        return@withContext runCatching {
            val json = JSONObject(raw)
            StoredTokens(
                accessToken = json.getString("accessToken"),
                refreshToken = json.optString("refreshToken", ""),
                expiresAt = Instant.ofEpochSecond(json.getLong("expiresAt")),
            )
        }.getOrNull()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_TOKENS).apply()
    }
}

data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
)
