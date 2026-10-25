package app.umuo.android

import android.media.MediaCodecList
import android.media.MediaFormat
import app.umuo.core.playback.*

object DeviceCapabilities {
    fun profile(): DeviceProfile {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun supports(mime: String) = codecs.any { info ->
            info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                info.getCapabilitiesForType(mime).isFormatSupported(MediaFormat.createVideoFormat(mime, 1920, 1080))
            }.getOrDefault(false)
        }
        val video = buildList { if(supports("video/avc")) add("h264"); if(supports("video/hevc")) add("hevc") }
        val audioMime = codecs.flatMap { it.supportedTypes.toList() }.toSet()
        val audio = mapOf("audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3", "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/flac" to "flac")
            .filterKeys { it in audioMime }.values
        return DeviceProfile(directPlayProfiles = if(video.isEmpty() || audio.isEmpty()) emptyList() else
            listOf(DirectPlayProfile("mp4,m4v,mkv,mov,ts,webm", video.joinToString(","), audio.joinToString(","))))
    }
}
