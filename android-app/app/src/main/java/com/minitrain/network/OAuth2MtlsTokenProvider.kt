package com.minitrain.network

import android.content.Context
import com.minitrain.security.SecureTokenStorage
import com.minitrain.security.StoredTokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val REFRESH_THRESHOLD: Duration = Duration.ofHours(12)

interface MtlsBearerTokenProvider {
    suspend fun currentTokens(): BearerTokens
    suspend fun refreshTokens(): BearerTokens
}

class OAuth2MtlsTokenProvider(
    private val context: Context,
    private val oauthClient: HttpClient,
    private val tokenEndpoint: String,
    private val clientId: String,
    private val scope: String,
    private val storage: SecureTokenStorage = SecureTokenStorage(context),
) : MtlsBearerTokenProvider {

    override suspend fun currentTokens(): BearerTokens {
        val stored = storage.read()
        val now = Instant.now()
        if (stored != null && stored.expiresAt.isAfter(now.plus(REFRESH_THRESHOLD))) {
            return stored.toBearerTokens()
        }
        return refreshTokens()
    }

    override suspend fun refreshTokens(): BearerTokens {
        val stored = storage.read()
        val response = requestToken(stored?.refreshToken)
        val expiresAt = Instant.now().plusSeconds(response.expiresIn)
        val newTokens = StoredTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: stored?.refreshToken.orEmpty(),
            expiresAt = expiresAt,
        )
        storage.write(newTokens)
        return newTokens.toBearerTokens()
    }

    private suspend fun requestToken(refreshToken: String?): OAuthTokenResponse {
        val parameters = Parameters.build {
            append("client_id", clientId)
            append("scope", scope)
            if (refreshToken.isNullOrEmpty()) {
                append("grant_type", "client_credentials")
            } else {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            }
        }
        val response: HttpResponse = oauthClient.submitForm(
            url = tokenEndpoint,
            formParameters = parameters,
        ) {
            contentType(ContentType.Application.FormUrlEncoded)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("OAuth2 token endpoint returned ${'$'}{response.status}")
        }
        return response.body()
    }
}

@Serializable
private data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

private fun StoredTokens.toBearerTokens(): BearerTokens = BearerTokens(accessToken, refreshToken)
