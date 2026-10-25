package app.umuo.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.LibraryRepository
import app.umuo.core.model.*
import kotlin.test.*

class ConnectionDraftTest {
    private val active=SourceConnection("a","NAS",SourceKind.WEBDAV,"https://nas/dav/","alice")
    @Test fun failedDraftSurvivesRestartWithoutChangingActiveLibrary() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UmuoDatabase.Schema.create(driver)
        try {
            val r=LibraryRepository(UmuoDatabase(driver))
            r.saveConnection(active)
            val item=MediaItem(MediaId("a","video"),"Movie")
            r.upsert(item,"/");r.savePosition(item.id,12000)
            r.saveDraft(active.copy(baseUrl="https://wrong/dav/",username="other"))
            val restored=LibraryRepository(UmuoDatabase(driver))
            assertEquals(active,restored.connections().single())
            assertEquals("https://wrong/dav/",restored.drafts().single().baseUrl)
            assertEquals(12000,restored.position(item.id));assertEquals(1,restored.items("a").size)
            restored.discardDraft("a");assertTrue(restored.drafts().isEmpty());assertEquals(12000,restored.position(item.id))
        } finally {driver.close()}
    }
    @Test fun candidateCredentialsOnlyReplaceActiveAfterSuccessfulCommit() {
        val store=MemorySecrets();store.write("a:password","good");store.write("a:token","old")
        val candidate=PendingCredentials(store,"a",inheritActive=true,reuseDraft=false)
        candidate.write("a:password","wrong");candidate.write("a:token","candidate")
        assertEquals("good",store.read("a:password"));assertEquals("old",store.read("a:token"))
        val resumed=PendingCredentials(store,"a",inheritActive=true,reuseDraft=true)
        assertEquals("wrong",resumed.read("a:password"))
        assertNull(resumed.read("a:token"))
        resumed.write("a:password","new")
        resumed.write("a:token","validated")
        assertFails {resumed.commit {error("database unavailable")}}
        assertEquals("good",store.read("a:password"));assertEquals("old",store.read("a:token"))
        resumed.commit {}
        assertEquals("new",store.read("a:password"));assertEquals("validated",resumed.read("a:token"))
        assertNull(store.read("draft:a:password"));assertNull(store.read("draft:a:token"))
        store.write("a:token","refreshed");assertEquals("refreshed",resumed.read("a:token"))
    }
    @Test fun changedIdentityDoesNotInheritActiveCredentials() {
        val store=MemorySecrets();store.write("a:password","old-secret");store.write("a:token","old-token")
        val candidate=PendingCredentials(store,"a",inheritActive=false,reuseDraft=false)
        assertNull(candidate.read("a:password"));assertNull(candidate.read("a:token"))
        candidate.write("a:password","new-secret");candidate.commit {}
        assertEquals("new-secret",store.read("a:password"));assertNull(store.read("a:token"))
    }
    @Test fun versionOneMigrationPreservesExistingConnectionsAndPositions() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UmuoDatabase.Schema.create(driver)
        try {
            val r=LibraryRepository(UmuoDatabase(driver));r.saveConnection(active)
            val id=MediaId("a","video");r.savePosition(id,9000)
            driver.execute(null,"DROP TABLE connection_drafts",0)
            UmuoDatabase.Schema.migrate(driver,1,2)
            val restored=LibraryRepository(UmuoDatabase(driver))
            assertEquals(active,restored.connections().single());assertEquals(9000,restored.position(id))
            restored.saveDraft(active);assertEquals(1,restored.drafts().size)
        } finally {driver.close()}
    }
}
