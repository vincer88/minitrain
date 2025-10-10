package com.minitrain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minitrain.network.MtlsBearerTokenProvider
import com.minitrain.network.MtlsCredentialStore
import com.minitrain.network.SecureWebSocketClientFactory
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class SecureWebSocketIntegrationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun secureSessionWithMutualTlsIsEstablished() = runBlocking {
        val clientCertificate = HeldCertificate.Builder()
            .commonName("minitrain-client")
            .build()
        val serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()

        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .heldCertificate(clientCertificate)
            .build()

        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.requestClientAuth()

        val message = "hello-train"
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send(message)
                        webSocket.close(1000, "bye")
                    }
                },
            ),
        )

        val credentialStore = object : MtlsCredentialStore {
            override fun keyManager(): X509KeyManager = clientCertificates.keyManager
            override fun trustManager(): X509TrustManager = clientCertificates.trustManager
        }
        val tokenProvider = object : MtlsBearerTokenProvider {
            override suspend fun currentTokens(): BearerTokens = BearerTokens("test-token", "refresh")
            override suspend fun refreshTokens(): BearerTokens = currentTokens()
        }

        val httpClient = SecureWebSocketClientFactory.create(
            credentialStore = credentialStore,
            expectedHostname = "localhost",
            tokenProvider = tokenProvider,
        )

        val url = server.url("/ws")
        val session = httpClient.webSocketSession(method = HttpMethod.Get, url = Url(url.toString().replace("http", "wss")))
        val frame = session.incoming.receive() as Frame.Text
        assertEquals(message, frame.readText())
        session.close()
        httpClient.close()
    }
}
