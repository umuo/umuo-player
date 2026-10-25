package app.umuo.core

import app.umuo.core.network.ServerAddress
import kotlin.test.*

class ServerAddressTest {
    @Test fun sourceDefaultsAndProxyAddressPreserveExplicitPorts() {
        assertEquals("80",ServerAddress.defaultPort(app.umuo.core.model.SourceKind.WEBDAV))
        assertEquals("/dav",ServerAddress.defaultPath(app.umuo.core.model.SourceKind.WEBDAV))
        for(kind in listOf(app.umuo.core.model.SourceKind.EMBY,app.umuo.core.model.SourceKind.JELLYFIN)) {
            assertEquals("8096",ServerAddress.defaultPort(kind))
            assertEquals("8920",ServerAddress.defaultPort(kind,true))
            assertEquals("/",ServerAddress.defaultPath(kind))
            val address=ServerAddress.parse("https://media.example:8443/proxy/")
            assertEquals("8443",address.port);assertEquals("/proxy/",address.path)
        }
    }

    @Test fun defaultsAndProxyPathRoundTrip() {
        assertEquals("http://nas.example/dav/",ServerAddress("nas.example").baseUrl())
        val parsed=ServerAddress.parse("https://nas.example:8443/proxy/dav/")
        assertTrue(parsed.https)
        assertEquals("8443",parsed.port)
        assertEquals("/proxy/dav/",parsed.path)
        assertEquals("https://nas.example:8443/proxy/dav/",parsed.baseUrl())
        assertEquals("/",ServerAddress.parse("http://nas.example/").path)
    }
    @Test fun ipv6AndValidation() {
        assertEquals("http://[::1]:8080/dav/",ServerAddress("::1","8080").baseUrl())
        listOf("0","65536","bad","").forEach { assertFails {ServerAddress("nas.example",it).baseUrl()} }
        listOf("", "user@host", "host/path", "host name").forEach {assertFails {ServerAddress(it).baseUrl()}}
        assertFails {ServerAddress.parse("https://user:password@host/dav")}
        assertFails {ServerAddress.parse("http://")}
        assertFails {ServerAddress("host",path="/dav?token=private").baseUrl()}
    }
}
