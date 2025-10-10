package com.minitrain.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.internal.platform.Platform

/**
 * Configure a Ktor [HttpClient] capable of negotiating secure WebSocket sessions with mutual TLS.
 */
object SecureWebSocketClientFactory {
    fun create(
        credentialStore: MtlsCredentialStore,
        expectedHostname: String,
        tokenProvider: MtlsBearerTokenProvider,
    ): HttpClient {
        val keyManager = credentialStore.keyManager()
        val trustManager = credentialStore.trustManager()
        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }
        val connectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3)
            .allEnabledCipherSuites()
            .build()

        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val hostnameVerifier = HostnameVerifier { hostname, session ->
            hostname == expectedHostname && defaultVerifier.verify(hostname, session)
        }

        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json()
            }
            install(WebSockets)
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 30_000
            }
            install(Auth) {
                bearer {
                    loadTokens {
                        tokenProvider.currentTokens()
                    }
                    refreshTokens {
                        tokenProvider.refreshTokens()
                    }
                }
            }
            engine {
                config {
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    hostnameVerifier(hostnameVerifier)
                    connectionSpecs(listOf(connectionSpec))
                    protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                }
            }
            defaultRequest {
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        }
    }
}
