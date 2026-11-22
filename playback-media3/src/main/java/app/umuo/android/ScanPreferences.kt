package app.umuo.android

import android.content.Context
import app.umuo.core.library.ScanSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("scan",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(runCatching {ScanSpeed.valueOf(prefs.getString("speed",null).orEmpty())}.getOrDefault(ScanSpeed.CONSERVATIVE))
    val speed=mutable.asStateFlow()
    private val previewsMutable=MutableStateFlow(prefs.getBoolean("autoPreviews",true))
    val autoPreviews=previewsMutable.asStateFlow()
    fun setAutoPreviews(enabled: Boolean) {prefs.edit().putBoolean("autoPreviews",enabled).apply();previewsMutable.value=enabled}
    fun setSpeed(value: ScanSpeed) {prefs.edit().putString("speed",value.name).apply();mutable.value=value}
    fun lastRequest(source: String): Long? = if(prefs.contains("request:$source"))prefs.getLong("request:$source",0) else null
    fun recordRequest(source: String,time: Long) {prefs.edit().putLong("request:$source",time).apply()}
    fun blockedUntil(source: String): Long = prefs.getLong("blocked:$source",0)
    fun block(source: String,until: Long) {prefs.edit().putLong("blocked:$source",until).apply()}
    fun clearSource(source: String) { prefs.edit().remove("request:$source").remove("blocked:$source").apply() }
}
