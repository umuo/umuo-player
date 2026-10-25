package app.umuo.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GestureSettings(val seekSeconds: Int = 10, val sensitivity: Float = 1f, val holdSpeed: Float = 2f)

class PlaybackPreferences(context: Context) {
    private val preferences=context.getSharedPreferences("playback",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(GestureSettings(
        preferences.getInt("seekSeconds",10).takeIf {it in listOf(5,10,30)} ?: 10,
        preferences.getFloat("sensitivity",1f).takeIf {it.isFinite()}?.coerceIn(.5f,2f) ?: 1f,
        preferences.getFloat("holdSpeed",2f).takeIf {it in listOf(1.5f,2f,3f)} ?: 2f
    ))
    val state=mutable.asStateFlow()
    fun update(settings: GestureSettings) {
        require(settings.seekSeconds in listOf(5,10,30) && settings.sensitivity.isFinite() && settings.sensitivity in .5f..2f && settings.holdSpeed in listOf(1.5f,2f,3f))
        preferences.edit().putInt("seekSeconds",settings.seekSeconds).putFloat("sensitivity",settings.sensitivity).putFloat("holdSpeed",settings.holdSpeed).apply()
        mutable.value=settings
    }
}
