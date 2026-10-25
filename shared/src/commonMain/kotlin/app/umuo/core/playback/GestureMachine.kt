package app.umuo.core.playback

/** 与视图、网络和播放器解耦，取消手势与生命周期中断走同一条恢复路径。 */
class GestureMachine {
    enum class Mode { IDLE, PRESSING, BOOST, SEEK, BRIGHTNESS, VOLUME }
    var mode = Mode.IDLE
        private set
    private var previousSpeed = 1f
    fun down(speed: Float) { previousSpeed = speed; mode = Mode.PRESSING }
    fun hold(): Boolean { if (mode != Mode.PRESSING) return false; mode = Mode.BOOST; return true }
    fun drag(horizontal: Boolean, left: Boolean): Boolean {
        if (mode !in listOf(Mode.PRESSING, Mode.SEEK, Mode.BRIGHTNESS, Mode.VOLUME)) return false
        if (mode == Mode.PRESSING) mode = if (horizontal) Mode.SEEK else if (left) Mode.BRIGHTNESS else Mode.VOLUME
        return true
    }
    fun release(): Float? { val restore = if (mode == Mode.BOOST) previousSpeed else null; mode = Mode.IDLE; return restore }
}

/** 使用触控区域像素高度归一化；不要在窗口 LayoutParams 作用域内读取同名 height。 */
fun brightnessAfterDrag(initial: Float, deltaY: Float, viewportHeight: Int): Float {
    val start=initial.coerceIn(.02f,1f)
    if(viewportHeight<=0)return start
    return (start-deltaY/viewportHeight.toFloat()).coerceIn(.02f,1f)
}
