import SwiftUI
import UmuoShared

@main
struct UmuoProbeApp: App {
    private let probe = ArchitectureProbe()
    var body: some Scene {
        WindowGroup {
            VStack(spacing: 24) {
                Image(systemName: "play.rectangle.fill").font(.system(size: 64))
                Text("Umuo · Apple Architecture Probe").font(.title)
                Text(probe.sampleTitle())
                Text(probe.platformBoundary()).font(.caption)
                Text("这是共享核心验证壳，尚未接入 Apple 播放引擎。")
            }.padding(40)
        }
    }
}
