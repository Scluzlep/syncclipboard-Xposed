package com.scluzlep.syncclipboard.protocol

import com.scluzlep.syncclipboard.model.ServerNode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsHttpFactory {

    fun normalizeBaseUrl(rawUrl: String): HttpUrl {
        val trimmed = rawUrl.trim().trimEnd('/')
        val withSlash = "$trimmed/"
        return withSlash.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid server URL: $rawUrl")
    }

    fun basicAuthHeader(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    fun createClient(
        node: ServerNode,
        connectTimeoutSec: Long = 10L,
        readTimeoutSec: Long = 60L,
        writeTimeoutSec: Long = 60L,
        callTimeoutSec: Long = 0L
    ): OkHttpClient {
        val baseUrl = normalizeBaseUrl(node.baseUrl)
        if (!baseUrl.isHttps && !node.allowCleartext) {
            throw SecurityException("Cleartext HTTP is disabled for node '${node.name}'. Enable allowCleartext or use HTTPS.")
        }

        val authHeader = if (node.username.isNotEmpty() || node.password.isNotEmpty()) {
            basicAuthHeader(node.username, node.password)
        } else {
            null
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .pingInterval(25L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (callTimeoutSec > 0L) {
            builder.callTimeout(callTimeoutSec, TimeUnit.SECONDS)
        }

        if (authHeader != null) {
            builder.addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", authHeader)
                    .build()
                chain.proceed(req)
            })
        }

        val pinnedSha = node.customCertSha256?.takeIf { it.isNotBlank() }?.let {
            ProfileHashUtils.normalizeSha256(it)
        }
        if (pinnedSha != null && baseUrl.isHttps) {
            val pinTrustManager = createPinnedTrustManager(pinnedSha)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(pinTrustManager), null)
            }
            builder.sslSocketFactory(sslContext.socketFactory, pinTrustManager)
            builder.hostnameVerifier(HostnameVerifier { _, session ->
                val certs = runCatching { session.peerCertificates }.getOrNull() ?: return@HostnameVerifier false
                certs.any { cert ->
                    val sha = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    ProfileHashUtils.bytesToUpperHex(sha).equals(pinnedSha, ignoreCase = true)
                }
            })
        }

        return builder.build()
    }

    private fun createPinnedTrustManager(expectedSha256Upper: String): X509TrustManager {
        val systemTrust = defaultSystemTrustManager()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                systemTrust?.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty server certificate chain")
                }
                val matched = chain.any { cert ->
                    val actual = ProfileHashUtils.sha256Hex(cert.encoded)
                    actual.equals(expectedSha256Upper, ignoreCase = true)
                }
                if (!matched) {
                    val leaf = ProfileHashUtils.sha256Hex(chain[0].encoded)
                    throw CertificateException(
                        "Pinned certificate SHA-256 mismatch. Expected $expectedSha256Upper, leaf was $leaf"
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTrust?.acceptedIssuers ?: emptyArray()
        }
    }

    private fun defaultSystemTrustManager(): X509TrustManager? {
        return runCatching {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()
    }
}
