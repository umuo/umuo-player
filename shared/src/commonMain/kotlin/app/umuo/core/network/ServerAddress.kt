package app.umuo.core.network

import io.ktor.http.Url
import app.umuo.core.model.SourceKind

/** 表单字段和协议地址之间的唯一转换；支持反向代理路径和 IPv6。 */
data class ServerAddress(val host: String, val port: String = "80", val path: String = "/dav", val https: Boolean = false) {
    fun baseUrl(): String {
        val cleanHost = host.trim().removeSurrounding("[", "]")
        require(cleanHost.isNotEmpty() && cleanHost.none { it.isWhitespace() || it in "/?#@" }) { "请填写服务器域名或 IP" }
        val number = port.toIntOrNull()
        require(number != null && number in 1..65535) { "端口必须在 1–65535 之间" }
        require('?' !in path && '#' !in path) { "路径不能包含参数或片段" }
        val authority = if(':' in cleanHost) "[$cleanHost]" else cleanHost
        return normalizedBase("${if(https) "https" else "http"}://$authority:$number/${path.trim().trimStart('/')}")
    }
    companion object {
        fun defaultPort(kind: SourceKind, https: Boolean = false): String =
            if(kind==SourceKind.WEBDAV) {if(https) "443" else "80"} else {if(https) "8920" else "8096"}
        fun defaultPath(kind: SourceKind): String = if(kind==SourceKind.WEBDAV) "/dav" else "/"
        fun parse(raw: String): ServerAddress {
            require(raw.trim().substringAfter("://", "").substringBefore('/').isNotBlank()) { "请填写完整服务器地址" }
            val value = Url(normalizedBase(raw))
            return ServerAddress(value.host.removeSurrounding("[", "]"),value.port.toString(),value.encodedPath,value.protocol.name == "https")
        }
    }
}
