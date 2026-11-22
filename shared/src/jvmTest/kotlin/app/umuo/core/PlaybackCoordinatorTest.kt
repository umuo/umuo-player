package app.umuo.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.umuo.core.db.UmuoDatabase
import app.umuo.core.library.LibraryRepository
import app.umuo.core.model.*
import app.umuo.core.playback.*
import app.umuo.core.source.MediaSourceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    private class Engine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackState())
        override fun prepare(plan: PlaybackPlan) { state.value=PlaybackState(ready=true,playing=true,durationMs=60_000,positionMs=2_000) }
        override fun play(){state.value=state.value.copy(playing=true,paused=false)}
        override fun pause(){state.value=state.value.copy(playing=false,paused=true)}
        override fun seekTo(positionMs:Long){state.value=state.value.copy(positionMs=positionMs)}
        fun startBuffering(){state.value=state.value.copy(ready=false,playing=false,buffering=true,paused=false)}
        override fun setSpeed(speed:Float){}
        override fun selectAudio(id:String){}
        override fun selectSubtitle(id:String?){}
        override fun release(){}
    }
    private class Source : MediaSourceProvider {
        override val connection=SourceConnection("s","fixture",SourceKind.JELLYFIN,"https://fixture.test/")
        override val capabilities=SourceCapabilities(serverProgress=true)
        val events=mutableListOf<Pair<PlaybackPlan,PlaybackEvent>>()
        val closedSessions=mutableListOf<String?>()
        var closed=false
        var resolves=0
        var lastRequestedResumeMs=0L
        override suspend fun authenticate(credentials:MediaCredentials)=connection
        override suspend fun browse(parent:String?,offset:Int)=MediaPage(emptyList())
        override suspend fun details(id:MediaId)=MediaItem(id,"fixture")
        override suspend fun resolve(request:PlaybackRequest):PlaybackPlan {
            resolves++
            lastRequestedResumeMs=request.item.resumeMs
            val session="sess-$resolves"
            return PlaybackPlan(request.item,"https://fixture.test/video-$resolves.mp4",sessionId=session)
        }
        override suspend fun report(plan:PlaybackPlan,event:PlaybackEvent){events+=plan to event}
        override suspend fun closeSession(plan:PlaybackPlan){closed=true;closedSessions+=plan.sessionId}
        override fun close(){}
    }
    @Test fun pauseAndStopSavePositionWithSingleStartAndOrderedEvents()=runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        try {
            val library=LibraryRepository(UmuoDatabase(driver));val engine=Engine();val source=Source()
            val coordinator=PlaybackCoordinator(engine,library,backgroundScope){fail(it)}
            val item=MediaItem(MediaId("s","v"),"Video")
            coordinator.open(source,PlaybackRequest(item,DeviceProfile()))
            advanceTimeBy(501);runCurrent()
            engine.seekTo(6_000);engine.pause();advanceTimeBy(501);runCurrent()
            coordinator.stop()
            assertEquals(listOf(PlaybackEventKind.START,PlaybackEventKind.PROGRESS,PlaybackEventKind.STOP),source.events.map{it.second.kind})
            assertEquals(6_000,library.position(item.id));assertTrue(source.closed)
        }finally{driver.close()}
    }
    @Test fun bufferingDoesNotEmitPausedProgressAndStopSavesSeekPosition()=runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        try {
            val library=LibraryRepository(UmuoDatabase(driver));val engine=Engine();val source=Source()
            val coordinator=PlaybackCoordinator(engine,library,backgroundScope){fail(it)}
            val item=MediaItem(MediaId("s","buf"),"BufferingVideo")
            coordinator.open(source,PlaybackRequest(item,DeviceProfile()))
            advanceTimeBy(501);runCurrent()
            engine.seekTo(18_000);engine.startBuffering();advanceTimeBy(1_501);runCurrent()
            assertTrue(source.events.none { (_, e) -> e.kind==PlaybackEventKind.PROGRESS && e.paused })
            coordinator.stop()
            assertEquals(18_000,library.position(item.id))
            assertEquals(18_000,source.events.last().second.positionMs)
            assertEquals(listOf(PlaybackEventKind.START,PlaybackEventKind.STOP),source.events.map {it.second.kind})
            assertTrue(source.closed)
        }finally{driver.close()}
    }
    @Test fun refreshPlanUpdatesSessionWithoutEmittingStartOrStop()=runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);UmuoDatabase.Schema.create(driver)
        try {
            val library=LibraryRepository(UmuoDatabase(driver));val engine=Engine();val source=Source()
            val coordinator=PlaybackCoordinator(engine,library,backgroundScope){fail(it)}
            val item=MediaItem(MediaId("s","refresh"),"SignedVideo")
            val initial=coordinator.open(source,PlaybackRequest(item,DeviceProfile()))
            assertEquals("sess-1",initial.sessionId)
            advanceTimeBy(501);runCurrent()
            val refreshed=coordinator.refreshPlan(15_000)
            assertEquals("https://fixture.test/video-2.mp4",refreshed?.uri)
            assertEquals("sess-2",refreshed?.sessionId)
            assertEquals(15_000,source.lastRequestedResumeMs)
            assertEquals(listOf(PlaybackEventKind.START),source.events.map {it.second.kind})
            assertEquals(listOf<String?>("sess-1"),source.closedSessions)
            coordinator.stop()
            assertEquals(listOf(PlaybackEventKind.START,PlaybackEventKind.STOP),source.events.map {it.second.kind})
            assertEquals("sess-2",source.events.last().first.sessionId)
            assertEquals(listOf<String?>("sess-1","sess-2"),source.closedSessions)
        }finally{driver.close()}
    }

    @Test fun nearCompletionClearsLongVideoResumeButPreservesShortClips() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UmuoDatabase.Schema.create(driver)
        try {
            val library = LibraryRepository(UmuoDatabase(driver))
            val engine = Engine()
            val source = Source()
            val coordinator = PlaybackCoordinator(engine, library, backgroundScope) { fail(it) }

            val longFilm = MediaItem(MediaId("s", "long"), "Long Film", durationMs = 600_000L)
            coordinator.open(source, PlaybackRequest(longFilm, DeviceProfile()))
            engine.state.value = PlaybackState(ready = true, playing = true, durationMs = 600_000L, positionMs = 575_000L)
            advanceTimeBy(501); runCurrent()
            coordinator.stop()
            assertEquals(0L, library.position(longFilm.id))
            assertTrue(source.events.last().second.completed)

            val shortSample = MediaItem(MediaId("s", "short"), "Short Clip", durationMs = 60_000L)
            coordinator.open(source, PlaybackRequest(shortSample, DeviceProfile()))
            engine.state.value = PlaybackState(ready = true, playing = true, durationMs = 60_000L, positionMs = 58_000L)
            advanceTimeBy(501); runCurrent()
            coordinator.stop()
            assertEquals(58_000L, library.position(shortSample.id))
            assertFalse(source.events.last().second.completed)
        } finally {
            driver.close()
        }
    }
}
