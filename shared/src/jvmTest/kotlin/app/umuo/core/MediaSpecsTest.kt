package app.umuo.core.library

import app.umuo.core.model.MediaId
import app.umuo.core.model.MediaItem
import app.umuo.core.model.MediaKind
import app.umuo.core.model.MetadataOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSpecsTest {
    @Test
    fun extractsResolutionDynamicRangeCodecAudioContainerAndNfoInOrder() {
        val item = MediaItem(
            id = MediaId("s1", "m1"),
            title = "Arrival",
            kind = MediaKind.MOVIE,
            path = "https://nas.local/Movies/Arrival%20(2016)/Arrival.2160p.HDR10+.HEVC.Atmos.mkv?token=secret",
            metadataOrigin = MetadataOrigin.NFO
        )
        assertEquals(listOf("4K", "HDR", "HEVC", "Atmos", "MKV", "NFO"), MediaSpecs.badges(item))
    }

    @Test
    fun extractsTagsAndOnlineOriginAndSkipsDirectories() {
        val online = MediaItem(
            id = MediaId("s1", "e1"),
            title = "Pilot",
            kind = MediaKind.EPISODE,
            path = "/series/Show.1080p.h264.AAC.mp4",
            tags = listOf("DoVi", "FLAC"),
            metadataOrigin = MetadataOrigin.ONLINE
        )
        assertEquals(listOf("1080P", "DV", "H.264", "FLAC", "MP4", "TMDB"), MediaSpecs.badges(online))

        val folder = MediaItem(
            id = MediaId("s1", "d1"),
            title = "Movies.2160p.HEVC.mkv",
            kind = MediaKind.DIRECTORY,
            path = "/dav/Movies.2160p.HEVC.mkv/"
        )
        assertEquals(emptyList(), MediaSpecs.badges(folder))
    }
}
