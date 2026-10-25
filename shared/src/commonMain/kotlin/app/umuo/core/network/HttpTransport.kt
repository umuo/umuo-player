package app.umuo.core.network

import app.umuo.core.model.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject.rows(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
fun normalizedBase(raw: String): String {
    val u = Url(raw.trim())
    require(u.protocol == URLProtocol.HTTP || u.protocol == URLProtocol.HTTPS) { "仅支持 HTTP(S) 地址" }
    require(u.user.isNullOrEmpty() && u.password.isNullOrEmpty() && u.parameters.isEmpty() && u.fragment.isEmpty()) { "地址不能包含账号、参数或片段" }
    return u.toString().trimEnd('/') + "/"
}
fun origin(raw: String): String = Url(raw).let { "${it.protocol.name}://${it.host}:${it.port}" }
fun resolveUrl(base: String, relative: String): String = URLBuilder(base).takeFromRelative(relative).buildString()
private fun URLBuilder.takeFromRelative(relative: String): URLBuilder {
    if (relative.startsWith("http://") || relative.startsWith("https://")) return takeFrom(relative)
    if (relative.startsWith('/')) {
        val root = "${protocol.name}://$host:$port"
        return takeFrom(root + relative)
    }
    return takeFrom(buildString().substringBefore('?').trimEnd('/') + "/" + relative)
}

open class SourceFailure(val status: Int, message: String) : Exception(message)

/** 每个来源拥有独立客户端；禁止自动重定向，避免向其他 origin 发送认证信息。 */
class HttpTransport(
    val baseUrl: String, private val client: HttpClient,
    private val headers: () -> Map<String, String> = { emptyMap() }
) {
    suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, body: String? = null,
                        extra: Map<String, String> = emptyMap()): HttpResponse {
        val url = resolveUrl(baseUrl, path)
        require(origin(url) == origin(baseUrl)) { "来源请求不能跨服务器携带凭据" }
        val response = client.request(url) {
            this.method = method
            headers { this@HttpTransport.headers().forEach { (k, v) -> append(k, v) }; extra.forEach { (k, v) -> append(k, v) } }
            if (body != null) setBody(body)
        }
        if (response.status.value !in 200..299) throw SourceFailure(response.status.value,
            when (response.status.value) {
                401, 403 -> "认证失败或没有访问权限"
                in 300..399 -> "服务器要求重定向，请填写最终服务地址"
                404 -> "资源不存在"
                else -> "服务器请求失败（${response.status.value}）"
            })
        return response
    }
    suspend fun probeMedia(path: String, anonymous: HttpClient): MediaProbe {
        val initial=resolveUrl(baseUrl,path)
        require(origin(initial)==origin(baseUrl))
        return probeMedia(initial,origin(baseUrl),client,anonymous,headers)
    }
    suspend fun probeRange(path: String): Boolean {
        val url = resolveUrl(baseUrl, path)
        require(origin(url) == origin(baseUrl))
        return client.prepareGet(url) {
            headers().forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=0-0")
        }.execute { response ->
            if (response.status.value !in 200..299) throw SourceFailure(response.status.value, "媒体访问失败（${response.status.value}）")
            // execute 在离开块时释放响应；忽略 Range 的服务器不会导致整部影片被读入内存。
            response.status == HttpStatusCode.PartialContent && response.headers[HttpHeaders.ContentRange]?.startsWith("bytes 0-0/") == true
        }
    }
    suspend fun text(path: String, limit: Int = 4 * 1024 * 1024): String {
        val url = resolveUrl(baseUrl, path)
        require(origin(url) == origin(baseUrl))
        return client.prepareGet(url) { headers().forEach { (k, v) -> header(k, v) } }.execute { response ->
            if(response.status.value !in 200..299) throw SourceFailure(response.status.value, "资料读取失败（${response.status.value}）")
            val bytes = response.bodyAsChannel().readRemaining(limit.toLong() + 1).readByteArray()
            require(bytes.size <= limit) { "资料文件过大" }
            bytes.decodeToString()
        }
    }
    suspend fun json(path: String, method: HttpMethod = HttpMethod.Get, body: JsonObject? = null): JsonObject {
        val text = request(path, method, body?.toString(), mapOf("Content-Type" to "application/json")).bodyAsText()
        return if (text.isBlank()) JsonObject(emptyMap()) else wireJson.parseToJsonElement(text).jsonObject
    }
    fun close() = client.close()
}

fun authenticatedClient(base: HttpClient, connection: SourceConnection, store: CredentialStore): HttpClient = base.config {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    if (connection.kind == SourceKind.WEBDAV) install(Auth) {
        basic {
            credentials { BasicAuthCredentials(connection.username, store.read("${connection.id}:password").orEmpty()) }
            sendWithoutRequest { false }
        }
        digest { credentials { DigestAuthCredentials(connection.username, store.read("${connection.id}:password").orEmpty()) } }
    }
}
