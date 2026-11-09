package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.ProxyConfig
import com.aicode.quotasentry.core.ProxyType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.Authenticator
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLException

/**
 * 基于 [java.net.HttpURLConnection] 的极简 HTTP 引擎（不引入 OkHttp）。
 *
 * 能力：
 * - 走 `Settings.proxy`：HTTP 代理用 `Proxy.Type.HTTP`，SOCKS5 用 `Proxy.Type.SOCKS`；
 *   代理认证 HTTP 侧额外发 `Proxy-Authorization: Basic`，SOCKS5 侧通过全局 [Authenticator]（仅对代理主机生效）。
 * - 连接 20s / 读取 30s 超时（SSE 放宽到 45s）、自动跟随 301/302/303/307/308（最多 5 跳）、gzip/deflate 解压。
 * - 默认浏览器 UA / Accept-Language；调用方可以覆盖。
 * - [sse] 负责逐行读取 `text/event-stream`，读到足够行数立即断开，避免等完整回答。
 *
 * 约定：所有失败都转成 [HttpResult.Fail]（中文原因），网络层不向调用方抛异常。
 * 非 2xx 的响应仍以 [HttpResult.Ok] 返回（带 code/body），由适配器决定是「Cookie 失效」还是「接口变更」。
 */
class HttpEngine(proxyConfig: ProxyConfig = ProxyConfig()) {

    private val proxy: Proxy? = buildProxy(proxyConfig)

    private val proxyAuthHeader: String? =
        if (proxyConfig.usable && proxyConfig.type == ProxyType.HTTP && proxyConfig.username.isNotBlank()) {
            basicAuth(proxyConfig.username, proxyConfig.password)
        } else {
            null
        }

    init {
        // HttpURLConnection 默认会静默丢弃 Origin 等受限头；关掉限制（Android 上无副作用）。
        runCatching { System.setProperty("sun.net.http.allowRestrictedHeaders", "true") }
        ProxyAuthenticator.install(proxyConfig)
    }

    // ------------------------------------------------------------------ 公开 API

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_BODY_BYTES
    ): HttpResult = execute(METHOD_GET, url, headers, null, null, maxBytes)

    fun postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
        maxBytes: Int = MAX_BODY_BYTES
    ): HttpResult = execute(METHOD_POST, url, headers, body, CONTENT_TYPE_JSON, maxBytes)

    fun postForm(
        url: String,
        headers: Map<String, String> = emptyMap(),
        fields: Map<String, String>,
        maxBytes: Int = MAX_BODY_BYTES
    ): HttpResult = execute(METHOD_POST, url, headers, encodeForm(fields), CONTENT_TYPE_FORM, maxBytes)

    /**
     * 以 POST 打开 `text/event-stream`，逐行读取，最多 [maxLines] 行后主动断开。
     *
     * 返回的 [HttpResponse.body] 是读到的原始行（用换行拼起来），调用方可用
     * [hasSseDataLine] 判断是否真的收到了数据帧；非 2xx 时 body 是错误响应体。
     */
    fun sse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
        contentType: String = CONTENT_TYPE_JSON,
        maxLines: Int = MAX_SSE_LINES
    ): HttpResult {
        val merged = mergeHeaders(headers) + mapOf("Accept" to "text/event-stream")
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = openConnection(url, METHOD_POST, merged, body.toByteArray(Charsets.UTF_8), contentType)
            val code = conn.responseCode
            val responseHeaders = readHeaders(conn)
            if (code !in 200..299) {
                val text = readBounded(openStream(conn), MAX_ERROR_BYTES)
                HttpResult.Ok(
                    HttpResponse(code, responseHeaders, text, conn.url.toString())
                )
            } else {
                val sample = StringBuilder()
                val reader = openStream(conn).bufferedReader(Charsets.UTF_8)
                var count = 0
                var keepReading = true
                while (keepReading && count < maxLines.coerceIn(1, 256)) {
                    val line = try {
                        reader.readLine()
                    } catch (t: Throwable) {
                        // 读到一半超时/断开：把已读到的行交给调用方判断，别丢掉
                        if (sample.isEmpty()) throw t
                        null
                    } ?: break
                    count++
                    if (sample.length < MAX_SSE_SAMPLE_CHARS) {
                        sample.append(line).append('\n')
                    }
                    // 已经拿到 data 帧就不必继续等后续 token
                    if (hasSseDataLine(line)) keepReading = false
                }
                runCatching { reader.close() }
                HttpResult.Ok(
                    HttpResponse(code, responseHeaders, sample.toString(), conn.url.toString())
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HttpResult.Fail(networkErrorText(t))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 把 [url] 的内容下载到 [dest]（用于 APK 自动更新）。
     *
     * - 跟随 3xx 跳转，默认 `Accept-Encoding: identity`，避免进度和实际字节对不上；
     * - [onProgress] 大约每 100ms 回调一次，总大小未知时 total = -1；
     * - 大小不匹配 / 非 2xx / 网络异常都返回 [HttpResult.Fail]，不抛异常。
     */
    fun downloadTo(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): HttpResult {
        var currentUrl = url
        var redirects = 0
        return try {
            while (true) {
                val merged = headers + ("Accept-Encoding" to "identity")
                val conn = openConnection(currentUrl, METHOD_GET, merged, null, null)
                try {
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && !location.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                        redirects++
                        currentUrl = resolveUrl(currentUrl, location)
                        continue
                    }
                    if (code !in 200..299) {
                        val snippet = runCatching {
                            readBounded(openStream(conn), MAX_ERROR_BYTES)
                        }.getOrDefault("")
                        val extra = snippet.trim().take(120)
                        return HttpResult.Fail(
                            "下载失败（HTTP $code）" + if (extra.isBlank()) "" else "：$extra"
                        )
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                    dest.parentFile?.mkdirs()
                    var downloaded = 0L
                    var lastNotify = 0L
                    val input = openStream(conn)
                    java.io.FileOutputStream(dest).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.nanoTime()
                            if (now - lastNotify > 100_000_000L) {
                                lastNotify = now
                                runCatching { onProgress(downloaded, total) }
                            }
                        }
                        out.flush()
                    }
                    runCatching { input.close() }
                    runCatching { onProgress(downloaded, total) }
                    if (total > 0 && downloaded < total) {
                        runCatching { dest.delete() }
                        return HttpResult.Fail("下载不完整（$downloaded/$total 字节），请重试")
                    }
                    return HttpResult.Ok(HttpResponse(200, emptyMap(), "", conn.url.toString()))
                } finally {
                    runCatching { conn.disconnect() }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            HttpResult.Fail("下载失败：无法完成跳转")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HttpResult.Fail(networkErrorText(t))
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
        maxBytes: Int
    ): HttpResult {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        var redirects = 0
        return try {
            while (true) {
                val conn = openConnection(
                    currentUrl,
                    currentMethod,
                    mergeHeaders(headers),
                    currentBody?.toByteArray(Charsets.UTF_8),
                    contentType
                )
                try {
                    val code = conn.responseCode
                    val responseHeaders = readHeaders(conn)
                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && !location.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                        redirects++
                        currentUrl = resolveUrl(currentUrl, location)
                        // 303 一律改 GET；301/302 对 POST 按浏览器习惯降级为 GET；307/308 保留方法与 body
                        if (code == 303 || ((code == 301 || code == 302) && currentMethod == METHOD_POST)) {
                            currentMethod = METHOD_GET
                            currentBody = null
                        }
                        continue
                    }
                    val text = readBounded(openStream(conn), maxBytes)
                    return HttpResult.Ok(HttpResponse(code, responseHeaders, text, conn.url.toString()))
                } finally {
                    runCatching { conn.disconnect() }
                }
            }
            HttpResult.Fail("请求失败：无法完成跳转")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HttpResult.Fail(networkErrorText(t))
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        contentType: String?
    ): java.net.HttpURLConnection {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy)
        } else {
            URL(url).openConnection()
        } as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        // 默认头（调用方传同名的会覆盖）
        connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
        connection.setRequestProperty("Accept-Language", DEFAULT_ACCEPT_LANGUAGE)
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
        proxyAuthHeader?.let { connection.setRequestProperty("Proxy-Authorization", it) }
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                runCatching { connection.setRequestProperty(name, value) }
            }
        }
        if (bodyBytes != null) {
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                contentType ?: CONTENT_TYPE_JSON
            )
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }
        }
        return connection
    }

    /** 打开响应流并按 Content-Encoding 解压；先探测 gzip 魔数，避免「头说有 gzip、实际明文」时崩掉。 */
    private fun openStream(connection: java.net.HttpURLConnection): InputStream {
        val raw: InputStream = runCatching {
            if (connection.responseCode >= 400) {
                connection.errorStream ?: connection.inputStream
            } else {
                connection.inputStream
            }
        }.getOrElse { connection.inputStream }
        val encoding = connection.contentEncoding?.lowercase().orEmpty()
        val gzipLike = encoding.contains("gzip") || encoding.contains("deflate")
        if (!gzipLike) return raw
        val pushback = PushbackInputStream(raw, 2)
        val first = pushback.read()
        val second = pushback.read()
        if (first >= 0) pushback.unread(first)
        if (second >= 0) pushback.unread(second)
        return when {
            first == 0x1f && second == 0x8b -> GZIPInputStream(pushback)
            encoding.contains("deflate") -> runCatching { InflaterInputStream(pushback) }
                .getOrDefault(pushback)
            else -> pushback
        }
    }

    private fun readHeaders(connection: java.net.HttpURLConnection): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        runCatching {
            connection.headerFields?.forEach { (key, values) ->
                if (key != null && !values.isNullOrEmpty()) {
                    result[key.lowercase()] = values.joinToString(", ")
                }
            }
        }
        return result
    }

    private fun readBounded(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        try {
            while (total < maxBytes) {
                val read = input.read(chunk)
                if (read < 0) break
                val allowed = minOf(read, maxBytes - total)
                if (allowed <= 0) break
                buffer.write(chunk, 0, allowed)
                total += allowed
            }
        } finally {
            runCatching { input.close() }
        }
        return buffer.toByteArray().toString(Charsets.UTF_8)
    }

    private fun mergeHeaders(headers: Map<String, String>): Map<String, String> = headers

    private fun resolveUrl(base: String, location: String): String =
        runCatching { URL(URL(base), location).toString() }.getOrDefault(location)

    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_PAGE_BYTES = 6 * 1024 * 1024
        const val MAX_ERROR_BYTES = 16 * 1024
        const val MAX_SSE_LINES = 16
        const val MAX_REDIRECTS = 5
        private const val MAX_SSE_SAMPLE_CHARS = 8 * 1024

        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_FORM = "application/x-www-form-urlencoded;charset=UTF-8"
        private const val METHOD_GET = "GET"
        private const val METHOD_POST = "POST"

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val DEFAULT_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

        internal fun buildProxy(config: ProxyConfig): Proxy? {
            if (!config.usable) return null
            val type = if (config.type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return runCatching {
                Proxy(type, InetSocketAddress(config.host.trim(), config.port))
            }.getOrNull()
        }

        internal fun basicAuth(username: String, password: String): String =
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

        internal fun encodeForm(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (key, value) ->
                java.net.URLEncoder.encode(key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(value, "UTF-8")
            }

        /** 是否读到了真正的 SSE data 帧（排除空行、注释、`[DONE]`）。 */
        internal fun hasSseDataLine(line: String): Boolean {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return false
            val payload = trimmed.removePrefix("data:").trim()
            return payload.isNotEmpty() && payload != "[DONE]"
        }

        internal fun sseSampleHasData(sample: String): Boolean =
            sample.lineSequence().any { hasSseDataLine(it) }

        /** 把网络异常翻译成用户能操作的中文原因。 */
        fun networkErrorText(t: Throwable): String = when (t) {
            is UnknownHostException -> "域名解析失败，请检查网络或代理设置"
            is ConnectException -> "无法连接服务器，请检查网络或代理是否可用"
            is SocketTimeoutException -> "网络超时，请检查网络或代理"
            is SSLException -> "HTTPS 连接失败，代理可能不支持该站点"
            is SocketException -> "网络连接中断：" + (t.message ?: "请检查网络或代理")
            is IOException -> "网络错误：" + (t.message ?: "请检查网络或代理")
            is IllegalArgumentException -> "请求地址无效"
            else -> t.message?.takeIf { it.isNotBlank() } ?: "网络错误（${t.javaClass.simpleName}）"
        }
    }
}

/** 一次 HTTP 响应。headers 的 key 已统一小写。 */
data class HttpResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val finalUrl: String = ""
) {
    val ok: Boolean get() = code in 200..299

    fun header(name: String): String? = headers[name.lowercase()]

    /** 错误响应体的可读摘要（截断，避免把整页 HTML 塞进 UI）。 */
    fun snippet(max: Int = 240): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        return if (text.length <= max) text else text.take(max) + "…"
    }
}

sealed interface HttpResult {
    data class Ok(val response: HttpResponse) : HttpResult
    data class Fail(val message: String) : HttpResult
}

/** 代理认证：只在「请求发往用户配置的代理主机」时提供凭据，避免把密码发给业务服务器。 */
internal object ProxyAuthenticator {

    @Volatile
    private var config: ProxyConfig? = null

    private val installed = AtomicBoolean(false)

    fun install(proxyConfig: ProxyConfig) {
        config = if (proxyConfig.usable && proxyConfig.username.isNotBlank()) proxyConfig else null
        if (config == null) return
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    val current = config ?: return null
                    if (requestorType != RequestorType.PROXY) return null
                    if (!requestingHost.equals(current.host.trim(), ignoreCase = true)) return null
                    if (requestingPort != current.port) return null
                    return PasswordAuthentication(
                        current.username,
                        current.password.toCharArray()
                    )
                }
            })
        }
    }
}
