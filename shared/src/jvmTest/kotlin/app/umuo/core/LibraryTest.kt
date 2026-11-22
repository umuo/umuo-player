package app.umuo.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.*
import app.umuo.core.metadata.*
import app.umuo.core.model.*
import app.umuo.core.playback.*
import app.umuo.core.source.webdav.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LibraryTest {
    @Test fun editingConnectionPreservesProgressUnlessIdentityChanges() {
        val (r,driver)=repository()
        try {
            val a=SourceConnection("a","NAS",SourceKind.WEBDAV,"https://nas/dav/","alice")
            val b=a.copy(id="b",username="bob")
            r.saveConnection(a);r.saveConnection(b)
            val item=MediaItem(MediaId("a","same-id"),"Old")
            val other=item.copy(id=MediaId("b","same-id"))
            r.upsert(item,"/");r.upsert(other,"/");r.savePosition(item.id,9000);r.savePosition(other.id,3000)
            r.match(item,item.copy(title="Override"));r.enqueue("a","/unfinished")
            r.saveConnection(a.copy(name="Renamed"))
            assertEquals(9000,r.position(item.id));assertEquals("Override",r.items("a").single().title)
            r.saveConnection(a.copy(username="charlie"))
            assertTrue(r.items("a").isEmpty());assertEquals(0,r.position(item.id))
            assertTrue(r.pending("a").isEmpty());assertTrue(r.automaticMatchAllowed(item.id))
            assertEquals(3000,r.position(other.id));assertEquals(1,r.items("b").size)
            assertEquals(2,r.connections().size)
        } finally {driver.close()}
    }
    private fun repository(): Pair<LibraryRepository,JdbcSqliteDriver> {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UmuoDatabase.Schema.create(driver)
        return LibraryRepository(UmuoDatabase(driver)) to driver
    }
    @Test fun sourceIdentityProgressOverridesAndQueueSurviveRepositoryRecreation() {
        val (r,driver)=repository()
        try {
            val a=MediaItem(MediaId("a","same-id"),"A");val b=a.copy(id=MediaId("b","same-id"),title="B")
            r.upsert(a,"/");r.upsert(b,"/");r.savePosition(a.id,1234);r.savePosition(b.id,5678)
            r.match(a,a.copy(title="Correct",metadataId="tmdb:1"));r.enqueue("a","/pending")
            val restored=LibraryRepository(UmuoDatabase(driver))
            assertEquals(1234,restored.position(a.id));assertEquals(5678,restored.position(b.id))
            assertEquals("Correct",restored.items("a").single().title)
            restored.saveDuration(a.id,60_000)
            assertEquals(60_000,restored.recent("a").single().durationMs)
            assertEquals(1234,restored.recent("a").single().resumeMs)
            assertEquals(listOf("/pending"),restored.pending("a"))
            restored.match(a,null);assertFalse(restored.automaticMatchAllowed(a.id));assertEquals("A",restored.items("a").single().title)
        }finally{driver.close()}
    }
    @Test fun filenameAndNfoPreserveEpisodeIdentity() {
        val item=FilenameParser.parse(MediaItem(MediaId("s","file"),"Planet.Earth.S02E03.1080p.mkv"))
        assertEquals("Planet Earth",item.series);assertEquals(2,item.season);assertEquals(3,item.episode)
        val result=NfoParser.apply(item,"""<episodedetails><title>中文集名</title><showtitle>地球</showtitle><season>2</season><episode>3</episode><plot>A &amp; B</plot><uniqueid type="tmdb">42</uniqueid><unknown>ignored</unknown></episodedetails>""")
        assertEquals(item.id,result.id);assertEquals("中文集名",result.title);assertEquals("A & B",result.overview);assertEquals("tmdb:42",result.metadataId)
        assertEquals(2024,FilenameParser.parse(item.copy(title="Arrival.2024.1080p.mp4")).year)
        assertFailsWith<IllegalArgumentException>{NfoParser.apply(item,"""<!DOCTYPE x [<!ENTITY x SYSTEM "file:///secret">]><movie/>""")}
    }
    @Test fun scanFailurePreservesCacheAndResumableQueue()=runTest {
        val (r,driver)=repository()
        val source="https://dav.test/videos/"
        val old=MediaItem(MediaId("dav",source+"old.mp4"),"Old")
        r.upsert(old,source)
        var healthy=false
        val client=HttpClient(MockEngine {request->
            if(!healthy)respond("unavailable",HttpStatusCode.ServiceUnavailable) else {
                assertEquals("PROPFIND",request.method.value)
                respond("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/videos/new.mp4</d:href><d:propstat><d:prop><d:displayname>New.2024.mp4</d:displayname><d:resourcetype/><d:getetag>v1</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",headers=headersOf("Content-Type","application/xml"))
            }
        })
        val p=WebDavProvider(SourceConnection("dav","DAV",SourceKind.WEBDAV,source),client,MemorySecrets())
        try {
            val scanner=LibraryScanner(r);scanner.scan(p)
            assertEquals("Old",r.items("dav").single().title);assertEquals(listOf(source),r.pending("dav"));assertEquals(1,scanner.state.value.failures)
            healthy=true;scanner.scan(p)
            assertEquals("New",r.items("dav").single().title);assertEquals(2024,r.items("dav").single().year);assertTrue(r.pending("dav").isEmpty())
            var searches=0
            val metadata=object:MetadataProvider {
                override suspend fun search(title:String,year:Int?,television:Boolean):List<MediaItem> {
                    searches++
                    return listOf(MediaItem(MediaId("tmdb","1"),"New",MediaKind.MOVIE,year=2024,metadataId="tmdb:1"))
                }
                override suspend fun details(id:String,television:Boolean):MediaItem=error("not needed")
            }
            assertEquals(0,searches) // 默认扫描没有元数据服务调用。
            scanner.scan(p,metadata);assertEquals(1,searches);assertEquals("tmdb:1",r.items("dav").single().metadataId)
            val matched=r.items("dav").single();r.match(matched,null)
            scanner.scan(p,metadata);assertEquals(1,searches);assertNull(r.items("dav").single().metadataId)

        }finally{p.close();client.close();driver.close()}
    }
    @Test fun gestureCancellationRestoresOriginalSpeed() {
        val g=GestureMachine();g.down(1.25f);assertTrue(g.hold());assertEquals(1.25f,g.release());assertEquals(GestureMachine.Mode.IDLE,g.mode);assertNull(g.release())
    }
    @Test fun batchItemsAndRecentMergeOverridesAccurately() {
        val (r,driver)=repository()
        try {
            val a=SourceConnection("a","DAV A",SourceKind.WEBDAV,"https://a.test/")
            val b=SourceConnection("b","DAV B",SourceKind.WEBDAV,"https://b.test/")
            r.saveConnection(a);r.saveConnection(b)
            val m1=MediaItem(MediaId("a","1"),"Raw One")
            val m2=MediaItem(MediaId("a","2"),"Raw Two")
            val m3=MediaItem(MediaId("a","3"),"Raw Three")
            val m4=MediaItem(MediaId("b","1"),"Other Raw")
            val count=r.transactionWithResult {
                r.upsert(m1,"/");r.upsert(m2,"/");r.upsert(m3,"/");r.upsert(m4,"/")
                r.match(m1,m1.copy(title="Matched One",metadataId="tmdb:10"))
                r.match(m2,null)
                r.match(m4,m4.copy(title="Other Matched",metadataId="tmdb:99"))
                r.savePosition(m1.id,1000);r.savePosition(m2.id,2000)
                3
            }
            assertEquals(3,count)
            val items=r.items("a").associateBy {it.id.value}
            assertEquals("Matched One",items["1"]?.title)
            assertEquals("tmdb:10",items["1"]?.metadataId)
            assertEquals("Raw Two",items["2"]?.title)
            assertFalse(r.automaticMatchAllowed(m2.id))
            assertEquals("Raw Three",items["3"]?.title)
            val recent=r.recent("a").associateBy {it.id.value}
            assertEquals(setOf("1","2"),recent.keys)
            assertEquals("Matched One",recent["1"]?.title)
            assertEquals("Raw Two",recent["2"]?.title)
            assertEquals("Other Matched",r.items("b").single().title)
        }finally{driver.close()}
    }

    @Test fun schemaMigrationV1ToV3PreservesPositionsAndMonotonicRecentOrder() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE indexed_media (source_id TEXT NOT NULL, media_id TEXT NOT NULL, parent TEXT NOT NULL, fingerprint TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(source_id, media_id))", 0)
            driver.execute(null, "CREATE TABLE metadata_override (source_id TEXT NOT NULL, media_id TEXT NOT NULL, payload TEXT, PRIMARY KEY(source_id, media_id))", 0)
            driver.execute(null, "CREATE TABLE playback_position (source_id TEXT NOT NULL, media_id TEXT NOT NULL, milliseconds INTEGER NOT NULL, PRIMARY KEY(source_id, media_id))", 0)
            driver.execute(null, "CREATE TABLE scan_queue (source_id TEXT NOT NULL, directory TEXT NOT NULL, PRIMARY KEY(source_id, directory))", 0)
            driver.execute(null, "CREATE TABLE connections (source_id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)", 0)

            val payload1 = """{"id":{"sourceId":"a","value":"1"},"title":"First"}"""
            val payload2 = """{"id":{"sourceId":"a","value":"2"},"title":"Second"}"""
            driver.execute(null, "INSERT INTO indexed_media VALUES ('a', '1', '/', '', '$payload1')", 0)
            driver.execute(null, "INSERT INTO indexed_media VALUES ('a', '2', '/', '', '$payload2')", 0)
            driver.execute(null, "INSERT INTO playback_position VALUES ('a', '1', 1000)", 0)
            driver.execute(null, "INSERT INTO playback_position VALUES ('a', '2', 2000)", 0)

            assertEquals(3L, UmuoDatabase.Schema.version)
            UmuoDatabase.Schema.migrate(driver, 1, UmuoDatabase.Schema.version)

            val r = LibraryRepository(UmuoDatabase(driver))
            assertEquals(listOf("2", "1"), r.recent("a").map { it.id.value })

            r.savePosition(MediaId("a", "1"), 4000, updatedAtMs = 5000L)
            r.savePosition(MediaId("a", "2"), 5000, updatedAtMs = 5000L)
            assertEquals(listOf("2", "1"), r.recent("a").map { it.id.value })

            r.savePosition(MediaId("a", "1"), 6000, updatedAtMs = 5000L)
            assertEquals(listOf("1", "2"), r.recent("a").map { it.id.value })
        } finally {
            driver.close()
        }
    }

    @Test fun findNextEpisodeOrdersBySeasonEpisodeAndScopedSource() {
        val s1e1 = MediaItem(MediaId("a", "ep1"), "E1", MediaKind.EPISODE, path = "https://dav.test/Show/S01E01.mp4", series = "Show", seriesKey = "show-1", season = 1, episode = 1)
        val s1e2 = MediaItem(MediaId("a", "ep2"), "E2", MediaKind.EPISODE, path = "https://dav.test/Show/S01E02.mp4", series = "Show", seriesKey = "show-1", season = 1, episode = 2)
        val s2e1 = MediaItem(MediaId("a", "ep3"), "E3", MediaKind.EPISODE, path = "https://dav.test/Show/S02E01.mp4", series = "Show", seriesKey = "show-1", season = 2, episode = 1)
        val otherSource = s1e2.copy(id = MediaId("b", "ep2"))
        val movie = MediaItem(MediaId("a", "m1"), "Movie", MediaKind.MOVIE, path = "https://dav.test/Movie.mp4")
        val all = listOf(s2e1, otherSource, s1e1, s1e2, movie)

        assertEquals(s1e2, LibraryCatalog.findNextEpisode(s1e1, all))
        assertEquals(s2e1, LibraryCatalog.findNextEpisode(s1e2, all))
        assertNull(LibraryCatalog.findNextEpisode(s2e1, all))
        assertNull(LibraryCatalog.findNextEpisode(movie, all))
        assertNull(LibraryCatalog.findNextEpisode(s1e1, listOf(s1e1, otherSource)))

        val dirEp1 = MediaItem(MediaId("a", "d1"), "S01E01", MediaKind.EPISODE, path = "https://dav.test/Dir/ep1.mp4", season = 1, episode = 1)
        val dirEp2 = MediaItem(MediaId("a", "d2"), "S01E02", MediaKind.EPISODE, path = "https://dav.test/Dir/ep2.mp4", season = 1, episode = 2)
        val otherDirEp3 = MediaItem(MediaId("a", "d3"), "S01E03", MediaKind.EPISODE, path = "https://dav.test/Other/ep3.mp4", season = 1, episode = 3)
        assertEquals(dirEp2, LibraryCatalog.findNextEpisode(dirEp1, listOf(dirEp2, otherDirEp3, dirEp1)))
        assertNull(LibraryCatalog.findNextEpisode(dirEp2, listOf(dirEp2, otherDirEp3, dirEp1)))
    }

    @Test fun removeConnectionClearsOnlyTargetSourceAndItemsReflectPositions() {
        val (r, driver) = repository()
        try {
            val a = SourceConnection("a", "NAS A", SourceKind.WEBDAV, "https://a.test/", "alice")
            val b = SourceConnection("b", "NAS B", SourceKind.WEBDAV, "https://b.test/", "bob")
            r.saveConnection(a)
            r.saveConnection(b)
            r.saveDraft(a.copy(name = "Draft A"))
            val m1 = MediaItem(MediaId("a", "1"), "Film A", MediaKind.MOVIE, metadataOrigin = MetadataOrigin.NFO, durationMs = 120_000)
            val m2 = MediaItem(MediaId("b", "1"), "Film B", MediaKind.MOVIE, metadataOrigin = MetadataOrigin.NFO, durationMs = 120_000)
            r.upsert(m1, "/")
            r.upsert(m2, "/")
            r.savePosition(m1.id, 45_000)
            r.savePosition(m2.id, 25_000)
            r.match(m1, m1.copy(title = "Override A"))
            r.enqueue("a", "/sub/")
            r.enqueue("b", "/sub/")

            assertEquals(45_000, r.items("a").single().resumeMs)
            assertEquals(45_000, r.detailed(m1).resumeMs)
            r.savePosition(m1.id, 0L)
            assertEquals(0L, r.detailed(m1.copy(resumeMs = 9_000L)).resumeMs)

            r.removeConnection("a")
            assertEquals(listOf(b), r.connections())
            assertTrue(r.drafts().none { it.id == "a" })
            assertTrue(r.items("a").isEmpty())
            assertEquals(0L, r.position(m1.id))
            assertTrue(r.pending("a").isEmpty())
            assertTrue(r.automaticMatchAllowed(m1.id))

            assertEquals(25_000, r.items("b").single().resumeMs)
            assertEquals(listOf("/sub/"), r.pending("b"))
        } finally {
            driver.close()
        }
    }
}
