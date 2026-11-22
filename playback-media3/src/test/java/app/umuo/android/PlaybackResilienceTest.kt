package app.umuo.android

import androidx.media3.common.PlaybackException
import app.umuo.core.model.MediaId
import app.umuo.core.model.MediaItem
import app.umuo.core.model.MediaKind
import app.umuo.core.network.MediaRedirectFailure
import app.umuo.core.playback.PlaybackFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResilienceTest {
    @Test fun clampSeekPositionLeavesSafeTailMargin() {
        assertEquals(0L, clampSeekPosition(-500L, 60_000L))
        assertEquals(59_500L, clampSeekPosition(60_000L, 60_000L))
        assertEquals(59_500L, clampSeekPosition(65_000L, 60_000L))
        assertEquals(900L, clampSeekPosition(1_200L, 1_000L))
        assertEquals(200L, clampSeekPosition(500L, 200L))
        assertEquals(0L, clampSeekPosition(-10L, 0L))
        assertEquals(12_000L, clampSeekPosition(12_000L, 0L))
    }

    @Test fun pipAspectRatioReducesAndClampsToSystemRange() {
        assertEquals(16 to 9, pipAspectRatio(0, 0))
        assertEquals(16 to 9, pipAspectRatio(1920, 1080))
        assertEquals(4 to 3, pipAspectRatio(1440, 1080))
        assertEquals(9 to 16, pipAspectRatio(1080, 1920))
        assertEquals(239 to 100, pipAspectRatio(3840, 1000))
        assertEquals(100 to 239, pipAspectRatio(1000, 3840))
    }

    @Test fun isOutOfRangeDetects416AndOutOfRangeCode() {
        assertTrue(isOutOfRangeCode(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, null))
        assertTrue(isOutOfRangeCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 416))
        assertFalse(isOutOfRangeCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 500))
        assertFalse(isOutOfRangeError(RuntimeException("network", IllegalStateException("offline"))))
    }

    @Test fun needsPlanRefreshDetectsSignedExpiryRedirectAndDecodeFailure() {
        assertTrue(needsPlanRefreshCode(PlaybackException.ERROR_CODE_DECODING_FAILED, null, false))
        for (status in listOf(401, 403, 404, 410)) {
            assertTrue(needsPlanRefreshCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status, false))
        }
        assertFalse(needsPlanRefreshCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500, false))
        assertTrue(needsPlanRefreshError(RuntimeException("wrap", MediaRedirectFailure("redirect failed"))))
        assertTrue(needsPlanRefreshError(RuntimeException("wrap", UnsafeMediaRedirect())))
        assertFalse(needsPlanRefreshError(RuntimeException("wrap", IllegalStateException("other"))))
    }

    @Test fun shouldAutoRecoverRequiresFirstFrameBudgetAndRecoverableError() {
        assertTrue(
            shouldAutoRecoverCode(
                hadFirstFrame = true,
                attempts = 0,
                maxAttempts = 2,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                failureKind = PlaybackFailureKind.NETWORK
            )
        )
        assertFalse(
            shouldAutoRecoverCode(
                hadFirstFrame = false,
                attempts = 0,
                maxAttempts = 2,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                failureKind = PlaybackFailureKind.NETWORK
            )
        )
        assertFalse(
            shouldAutoRecoverCode(
                hadFirstFrame = true,
                attempts = 2,
                maxAttempts = 2,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                failureKind = PlaybackFailureKind.NETWORK
            )
        )
        assertFalse(
            shouldAutoRecoverCode(
                hadFirstFrame = true,
                attempts = 0,
                maxAttempts = 2,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                failureKind = PlaybackFailureKind.TLS
            )
        )
        assertFalse(
            shouldAutoRecoverCode(
                hadFirstFrame = true,
                attempts = 0,
                maxAttempts = 2,
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                failureKind = PlaybackFailureKind.UNSUPPORTED
            )
        )
    }

    @Test fun seriesContinuationKeyGroupsBySourceAndSeriesOrDirectory() {
        val ep1 = MediaItem(MediaId("s1", "ep1"), "E1", MediaKind.EPISODE, path = "https://dav.test/Show/S01E01.mp4", series = "Show", seriesKey = "show-key")
        val ep2 = MediaItem(MediaId("s1", "ep2"), "E2", MediaKind.EPISODE, path = "https://dav.test/Show/S01E02.mp4", series = "Show", seriesKey = "show-key")
        val otherSource = ep2.copy(id = MediaId("s2", "ep2"))
        val movie = MediaItem(MediaId("s1", "m1"), "Movie", MediaKind.MOVIE, path = "https://dav.test/Movie.mp4")
        val folder = MediaItem(MediaId("s1", "dir"), "Show", MediaKind.SERIES, seriesKey = "show-key")

        assertEquals(seriesContinuationKey(ep1), seriesContinuationKey(ep2))
        assertFalse(seriesContinuationKey(ep1) == seriesContinuationKey(otherSource))
        assertNull(seriesContinuationKey(movie))
        assertNull(seriesContinuationKey(folder))

        val dirEp1 = MediaItem(MediaId("s1", "d1"), "S01E01", MediaKind.EPISODE, path = "https://dav.test/FolderA/ep1.mp4", season = 1, episode = 1)
        val dirEp2 = MediaItem(MediaId("s1", "d2"), "S01E02", MediaKind.EPISODE, path = "https://dav.test/FolderA/ep2.mp4", season = 1, episode = 2)
        val dirOtherSource = dirEp1.copy(id = MediaId("s2", "d1"))
        assertEquals(seriesContinuationKey(dirEp1), seriesContinuationKey(dirEp2))
        assertFalse(seriesContinuationKey(dirEp1) == seriesContinuationKey(dirOtherSource))
    }

    @Test fun tvSeekDeltaAcceleratesWithRepeatCountAndTimeLabelFormatsHours() {
        assertEquals(10_000L, tvSeekDeltaMs(10, 0))
        assertEquals(20_000L, tvSeekDeltaMs(10, 1))
        assertEquals(20_000L, tvSeekDeltaMs(10, 4))
        assertEquals(30_000L, tvSeekDeltaMs(10, 5))
        assertEquals(60_000L, tvSeekDeltaMs(10, 13))
        assertEquals(5_000L, tvSeekDeltaMs(5, 0))
        assertEquals(20_000L, tvSeekDeltaMs(5, 2))
        assertEquals(60_000L, tvSeekDeltaMs(30, 2))

        assertEquals("0:00", timeLabel(-500L))
        assertEquals("24:15", timeLabel((24 * 60 + 15) * 1_000L))
        assertEquals("2:05:09", timeLabel((2 * 3600 + 5 * 60 + 9) * 1_000L))
    }

    @Test fun subtitleBackgroundAndScaleModesHaveReadableLabels() {
        assertEquals(listOf("透明描边", "半透黑底", "暖黄字幕"), SubtitleBackground.entries.map { it.label })
        assertEquals(listOf("适应", "铺满", "拉伸"), VideoScaleMode.entries.map { it.label })
        assertEquals(GestureSettings().subtitleBackground, SubtitleBackground.NONE)
    }
}
