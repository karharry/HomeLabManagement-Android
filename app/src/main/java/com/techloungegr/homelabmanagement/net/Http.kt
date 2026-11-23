package com.techloungegr.homelabmanagement.net

import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.model.jsonParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class TlsPinError(message: String) : Exception(message)

private val trustAllForPinning = arrayOf<TrustManager>(object : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
})

/**
 * Trust-on-first-use HTTPS/WSS: the certificate chain isn't validated, but the leaf's SHA-256 is pinned the
 * first time and every later connection must present the same certificate.
 */
private class PinningTrust(private val host: String, private val port: Int) : X509ExtendedTrustManager() {
    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate presented")
        val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        val key = "tls.$host:$port"
        val pinned = Store.pinned(key)
        if (pinned == null) Store.pin(key, fp)
        else if (pinned != fp) throw CertificateException(
            "$host:$port is presenting a different TLS certificate than the one first trusted. Use “Forget pinned host key” in the machine's settings if this is expected (for example after renewing the certificate)."
        )
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object Http {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    }

    /** A client for [host]:[port]. With [pinSelfSigned] the certificate is pinned instead of CA-validated. */
    fun client(host: String, port: Int, tls: Boolean, pinSelfSigned: Boolean, timeoutSec: Long = 30): OkHttpClient {
        val b = base.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS)
        if (tls && pinSelfSigned) {
            val tm = PinningTrust(host, port)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    fun plain(timeoutSec: Long = 15): OkHttpClient = base.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()

    val JSON = "application/json".toMediaType()
    fun jsonBody(text: String): RequestBody = text.toRequestBody(JSON)
}

fun JsonElement?.orNull(): JsonElement? = if (this is JsonNull) null else this

fun describeNetError(e: Throwable): String {
    var t: Throwable? = e
    while (t != null) {
        val m = t.message
        if (t is CertificateException && !m.isNullOrBlank()) return m
        t = t.cause
    }
    return e.message ?: e.javaClass.simpleName
}

fun parse(text: String) = jsonParser.parseToJsonElement(text)
