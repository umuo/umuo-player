@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.umuo.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.*
import app.umuo.core.model.*
import app.umuo.core.source.webdav.WebDavProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ScanTrafficTest {
    private val empty="""<d:multistatus xmlns:d="DAV:"/>"""
    @Test fun authenticationHandshakeIsAlsoPacedAndOriginalClientSurvives()=runTest {
        val starts=mutableListOf<Long>()
        val client=HttpClient(MockEngine {request->
            starts+=testScheduler.currentTime
            if(request.headers[HttpHeaders.Authorization]==null)respond("",HttpStatusCode.Unauthorized,headersOf(HttpHeaders.WWWAuthenticate,"Basic realm=\"fixture\""))
            else respond(empty,headers=headersOf("Content-Type","application/xml"))
        })
        val secrets=MemorySecrets().also {it.write("s:password","fixture")}
        val source=WebDavProvider(SourceConnection("s","DAV",SourceKind.WEBDAV,"https://dav.test/","fixture"),client,secrets)
        val guarded=source.forScan(ScanTrafficGuard(nowMs={testScheduler.currentTime}))
        try {
            guarded.listEntries("https://dav.test/")
            assertEquals(listOf(0L,1000L),starts)
            guarded.close()
            assertTrue(source.listEntries("https://dav.test/").isEmpty())
        } finally {guarded.close();source.close();client.close()}
    }
    @Test fun responseLatencyCannotCompressTheNextRequestGap()=runTest {
        val gate=ScanTrafficGuard(nowMs={testScheduler.currentTime})
        gate.beforeRequest();advanceTimeBy(800);gate.responseReceived();gate.beforeRequest()
        assertEquals(1800,testScheduler.currentTime);assertEquals(2,gate.requests)
    }
    @Test fun cancellationDoesNotSendOrConsumeAnExtraRequest()=runTest {
        val gate=ScanTrafficGuard(nowMs={testScheduler.currentTime})
        gate.beforeRequest()
        val waiting=launch {gate.beforeRequest()}
        runCurrent();advanceTimeBy(500);waiting.cancelAndJoin()
        assertEquals(1,gate.requests)
        gate.beforeRequest();assertEquals(1000,testScheduler.currentTime);assertEquals(2,gate.requests)
    }
    @Test fun retryAfterDateAndMinimumCooldownAreRespected() {
        var blocked=0L
        val gate=ScanTrafficGuard(nowMs={0},onBlocked={blocked=it})
        assertFailsWith<ScanPaused>{gate.pauseForResponse(503,"Thu, 01 Jan 1970 00:02:00 GMT")}
        assertEquals(120_000,blocked)
        val malformed=ScanTrafficGuard(nowMs={10_000},onBlocked={blocked=it})
        assertFailsWith<ScanPaused>{malformed.pauseForResponse(429,"invalid")}
        assertEquals(70_000,blocked)
    }
    @Test fun rateLimitStopsWholeScanAndPreservesQueueAndCachedIndex()=runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        val repository=LibraryRepository(UmuoDatabase(driver))
        val old=MediaItem(MediaId("s","https://dav.test/old.mp4"),"Old")
        repository.upsert(old,"https://dav.test/")
        var requests=0;var healthy=false;var cooldown=0L
        val client=HttpClient(MockEngine {requests++
            if(healthy)respond(empty,headers=headersOf("Content-Type","application/xml"))
            else respond("",HttpStatusCode.TooManyRequests,headersOf(HttpHeaders.RetryAfter,"120"))
        })
        val source=WebDavProvider(SourceConnection("s","DAV",SourceKind.WEBDAV,"https://dav.test/"),client,MemorySecrets())
        val scanner=LibraryScanner(repository)
        try {
            scanner.scan(source,traffic=ScanTrafficGuard(nowMs={testScheduler.currentTime},onBlocked={cooldown=it}))
            assertEquals(1,requests);assertEquals(120_000,cooldown);assertNotNull(scanner.state.value.pausedReason)
            assertEquals(listOf(old),repository.items("s"));assertEquals(listOf("https://dav.test/"),repository.pending("s"))
            scanner.scan(source,traffic=ScanTrafficGuard(nowMs={testScheduler.currentTime},blockedUntilMs=cooldown))
            assertEquals(1,requests) // 手动再次点扫描，也不能绕过服务端冷却。
            advanceTimeBy(120_000);healthy=true
            scanner.scan(source,traffic=ScanTrafficGuard(nowMs={testScheduler.currentTime},blockedUntilMs=cooldown))
            assertEquals(2,requests);assertTrue(repository.pending("s").isEmpty())
        } finally {source.close();client.close();driver.close()}
    }
}
