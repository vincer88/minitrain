package com.minitrain.network

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Loads client certificate and private key material from a PKCS#12 keystore packaged with the app
 * or provisioned at runtime. The keystore should contain a single entry representing the client
 * credential.
 */
class KeystoreMtlsCredentialStore(
    private val keystoreBytes: ByteArray,
    private val password: CharArray,
) : MtlsCredentialStore {

    private val keyManager: X509KeyManager
    private val trustManager: X509TrustManager

    init {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(keystoreBytes.inputStream(), password)
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        keyManager = kmf.keyManagers.filterIsInstance<X509KeyManager>().first()
        trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun keyManager(): X509KeyManager = keyManager

    override fun trustManager(): X509TrustManager = trustManager
}
