package dev.tablegrip.core.driver.http

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A reply: its status and body, which [json] parses. */
class RestResponse(val status: Int, val body: String) {
    val ok: Boolean
        get() = status in 200..299

    fun json(): JsonElement =
        runCatching { Json.parseToJsonElement(body) }.getOrElse { throw IOException(tr("The server sent something that isn't JSON: {0}", body.take(200))) }
}

/**
 * HTTP for the engines TableGrip talks to over their REST APIs, with nothing to download. Profiles share the same
 * properties: `ssl=true` for HTTPS, `verifyPeer=false` to accept a self-signed certificate, `pathPrefix=/es` for a
 * server behind a reverse proxy. [authorization] is the `Authorization` header every request carries, if any.
 */
class RestClient(
    /** Names the server in error messages, e.g. "Elasticsearch". */
    private val engine: String,
    config: ConnectionConfig,
    host: String,
    port: Int,
    var authorization: String? = null,
    private val timeout: Duration = Duration.ofSeconds(60),
    /** HTTPS when the profile doesn't say: false, unless an engine's endpoints are HTTPS by nature. */
    defaultTls: Boolean = false,
) {
    private val tls = config.properties["ssl"]?.toBoolean() ?: defaultTls
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).apply {
            if (tls && config.properties["verifyPeer"]?.toBoolean() == false) sslContext(trustingEverything())
        }.build()

    val base: String = "${if (tls) "https" else "http"}://${formatHost(host)}:$port" + config.properties["pathPrefix"].orEmpty().trim().trimEnd('/').let {
        if (it.isEmpty() || it.startsWith("/")) it else "/$it"
    }

    /** Sends one request; [path] starts with `/` and may carry a query string. Never throws for an HTTP error status. */
    fun send(method: String, path: String, body: String? = null, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): RestResponse {
        val request =
            HttpRequest.newBuilder(URI(base + (if (path.startsWith("/")) path else "/$path")))
                .timeout(timeout)
                .method(method.uppercase(), if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) request.header("Accept", "application/json")
        if (body != null) request.header("Content-Type", contentType)
        authorization?.let { request.header("Authorization", it) }
        headers.forEach { (name, value) -> request.header(name, value) }
        val response =
            try {
                http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw IOException(tr("Couldn't reach {0} at {1}: {2}", engine, base, e.message ?: e.javaClass.simpleName), e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(tr("Interrupted"), e)
            }
        return RestResponse(response.statusCode(), response.body())
    }

    companion object {
        fun formatHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host

        fun basic(user: String, password: String?): String =
            "Basic " + java.util.Base64.getEncoder().encodeToString("$user:${password.orEmpty()}".toByteArray())

        private fun trustingEverything(): SSLContext =
            SSLContext.getInstance("TLS").apply {
                val trustAll =
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
    }
}
