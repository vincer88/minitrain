package com.minitrain.network

import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Contract describing how mutual TLS credentials are loaded on device.
 */
interface MtlsCredentialStore {
    fun keyManager(): X509KeyManager
    fun trustManager(): X509TrustManager
}
