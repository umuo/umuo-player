plugins {
 alias(libs.plugins.android.library)
 alias(libs.plugins.kotlin.android)
 alias(libs.plugins.kotlin.compose)
}
android {
 namespace = "app.umuo.playbackmedia3"
 compileSdk = 36
 defaultConfig { minSdk = 26 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 testImplementation(kotlin("test-junit"))
 implementation(platform(libs.compose.bom))
 implementation(libs.compose.ui)
 implementation(libs.compose.foundation)
 implementation(libs.compose.material3)
 api(project(":shared"))
 api(libs.compose.icons)
 api(libs.coil.compose)
 implementation(libs.ktor.core)
 implementation(libs.ktor.okhttp)
 implementation(libs.serialization)
 implementation(libs.sql.android)
 implementation(libs.coroutines)
 implementation(libs.media3.exoplayer)
 implementation(libs.media3.hls)
 implementation(libs.media3.ui)
 implementation(libs.media3.session)
 implementation(libs.media3.okhttp)
 implementation(libs.okhttp)
 implementation(libs.lifecycle.viewmodel.compose)
 implementation(libs.lifecycle.runtime.compose)
}
