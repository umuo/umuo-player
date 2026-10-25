package app.umuo.android

import app.umuo.core.model.*
import app.umuo.core.network.origin
import app.umuo.core.network.MEDIA_USER_AGENT
import okhttp3.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class UnsafeMediaRedirect : java.io.IOException("HTTPS downgrade rejected")

/** 同源才附加凭据；OkHttp 重定向不会将自定义 token 泄露给另一个站点。 */
fun sourceHttp(connection: SourceConnection, secrets: CredentialStore): OkHttpClient {
    val count = AtomicInteger()
    return OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", MEDIA_USER_AGENT).build())
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if(chain.call().request().url.isHttps && !request.url.isHttps)throw UnsafeMediaRedirect()
            val builder = request.newBuilder().removeHeader("X-Emby-Token")
            if (origin(request.url.toString()) == origin(connection.baseUrl)) {
                if (connection.kind == SourceKind.WEBDAV) {
                    if (request.header("Authorization") == null) secrets.read("${connection.id}:password")?.let {
                        builder.header("Authorization", Credentials.basic(connection.username, it))
                    }
                } else secrets.read("${connection.id}:token")?.let { builder.header("X-Emby-Token", it) }
            } else builder.removeHeader("Authorization")
            chain.proceed(builder.build())
        }
        .authenticator { _, response ->
            if (connection.kind != SourceKind.WEBDAV || origin(response.request.url.toString()) != origin(connection.baseUrl)) return@authenticator null
            var prior = response.priorResponse; var attempts = 0
            while (prior != null) { attempts++; prior = prior.priorResponse }
            if (attempts >= 2) return@authenticator null
            val challenge = response.headers("WWW-Authenticate").firstOrNull { it.startsWith("Digest ", true) } ?: return@authenticator null
            val params = Regex("""(\w+)=(?:"([^"]*)"|([^, ]+))""").findAll(challenge.removePrefix("Digest "))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            val realm = params["realm"] ?: return@authenticator null
            val nonce = params["nonce"] ?: return@authenticator null
            val algorithm = params["algorithm"] ?: "MD5"
            if (!algorithm.equals("MD5", true)) return@authenticator null
            val qop = params["qop"]?.split(',')?.map { it.trim() }?.let { if("auth" in it) "auth" else return@authenticator null }
            val password = secrets.read("${connection.id}:password") ?: return@authenticator null
            val uri = response.request.url.encodedPath + (response.request.url.encodedQuery?.let { "?$it" } ?: "")
            val nc = "%08x".format(count.incrementAndGet())
            val cnonce = UUID.randomUUID().toString().replace("-", "")
            fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val ha1 = md5("${connection.username}:$realm:$password")
            val ha2 = md5("${response.request.method}:$uri")
            val hash = if(qop == null) md5("$ha1:$nonce:$ha2") else md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            val header = "Digest username=${quote(connection.username)}, realm=${quote(realm)}, nonce=${quote(nonce)}, uri=${quote(uri)}, response=${quote(hash)}, algorithm=MD5" +
                (if(qop == null) "" else ", qop=auth, nc=$nc, cnonce=${quote(cnonce)}") + (params["opaque"]?.let { ", opaque=${quote(it)}" } ?: "")
            response.request.newBuilder().header("Authorization", header).build()
        }.build()
}
