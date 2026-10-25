package app.umuo.core

import app.umuo.core.metadata.FilenameParser
import app.umuo.core.model.*

/** SwiftUI 验证壳的稳定入口，证明共享模型和业务在 Apple target 可用。 */
class ArchitectureProbe {
    fun sampleTitle(): String = FilenameParser.parse(MediaItem(MediaId("fixture", "sample"), "Planet.Earth.S01E02.mkv")).title
    fun platformBoundary(): String = "KMP 媒体模型 / 协议 / 索引 → 平台播放与界面"
}
