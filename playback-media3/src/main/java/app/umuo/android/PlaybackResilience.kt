package app.umuo.android

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import app.umuo.core.model.MediaItem
import app.umuo.core.model.MediaKind
import app.umuo.core.network.MediaRedirectFailure
import app.umuo.core.playback.PlaybackFailureKind

internal fun seriesContinuationKey(item: MediaItem): String? {
    if (item.isFolder) return null
    val series = item.seriesKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: item.series?.trim()?.takeIf { it.isNotEmpty() }
    if (series != null) return "${item.id.sourceId}\u0000series:$series"
    if (item.kind == MediaKind.EPISODE || item.season != null || item.episode != null) {
        val parent = item.path.substringBeforeLast('/', "").trim()
        if (parent.isNotEmpty()) return "${item.id.sourceId}\u0000dir:$parent"
    }
    return null
}

internal fun clampSeekPosition(positionMs: Long, durationMs: Long): Long {
    if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
    val margin = when {
        durationMs > 2_000L -> 500L
        durationMs > 300L -> 100L
        else -> 0L
    }
    val maxTarget = (durationMs - margin).coerceAtLeast(0L)
    return positionMs.coerceIn(0L, maxTarget)
}

internal fun pipAspectRatio(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
    if (videoWidth <= 0 || videoHeight <= 0) return 16 to 9
    val ratio = videoWidth.toDouble() / videoHeight.toDouble()
    if (!ratio.isFinite() || ratio <= 0.0) return 16 to 9
    return when {
        ratio > 2.39 -> 239 to 100
        ratio < 1.0 / 2.39 -> 100 to 239
        else -> {
            val g = gcd(videoWidth, videoHeight)
            (videoWidth / g) to (videoHeight / g)
        }
    }
}

private fun gcd(a: Int, b: Int): Int {
    var x = kotlin.math.abs(a)
    var y = kotlin.math.abs(b)
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x.coerceAtLeast(1)
}

private fun httpStatusCode(error: Throwable): Int? =
    generateSequence(error) { it.cause }.take(8)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode

internal fun isOutOfRangeError(error: Throwable): Boolean =
    isOutOfRangeCode((error as? PlaybackException)?.errorCode, httpStatusCode(error))

internal fun isOutOfRangeCode(errorCode: Int?, httpStatus: Int?): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE || httpStatus == 416

internal fun needsPlanRefreshError(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val hasRedirectFailure = causes.any { it is MediaRedirectFailure || it is UnsafeMediaRedirect }
    val httpStatus = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode
    return needsPlanRefreshCode((error as? PlaybackException)?.errorCode, httpStatus, hasRedirectFailure)
}

internal fun needsPlanRefreshCode(errorCode: Int?, httpStatus: Int?, hasRedirectFailure: Boolean): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) return true
    if (hasRedirectFailure) return true
    return httpStatus in setOf(401, 403, 404, 410)
}

internal fun shouldAutoRecoverPlayback(
    hadFirstFrame: Boolean,
    attempts: Int,
    maxAttempts: Int,
    error: PlaybackException
): Boolean = shouldAutoRecoverCode(hadFirstFrame, attempts, maxAttempts, error.errorCode, playbackFailureKind(error))

internal fun shouldAutoRecoverCode(
    hadFirstFrame: Boolean,
    attempts: Int,
    maxAttempts: Int,
    errorCode: Int,
    failureKind: PlaybackFailureKind
): Boolean {
    if (!hadFirstFrame || attempts >= maxAttempts) return false
    if (failureKind == PlaybackFailureKind.TLS) return false
    return when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> false
        else -> true
    }
}

fun tvSeekDeltaMs(baseSeconds: Int, repeatCount: Int): Long {
    val base = baseSeconds.coerceIn(5, 60)
    val stepSeconds = when {
        repeatCount <= 0 -> base
        repeatCount <= 4 -> maxOf(base * 2, 20)
        repeatCount <= 12 -> maxOf(base * 3, 30)
        else -> 60
    }
    return stepSeconds * 1_000L
}
