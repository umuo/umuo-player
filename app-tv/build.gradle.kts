plugins {
 alias(libs.plugins.android.application)
 alias(libs.plugins.kotlin.android)
 alias(libs.plugins.kotlin.compose)
}
android {
 namespace = "app.umuo.tv"
 compileSdk = 36
 defaultConfig {
  applicationId = "app.umuo.player.tv"
  minSdk = 26
  targetSdk = 36
  versionCode = providers.gradleProperty("umuoVersionCode").get().toInt()
  versionName = providers.gradleProperty("umuoVersionName").get()
 }
 val signingPath = providers.environmentVariable("UMUO_KEYSTORE_PATH").orNull
 val releaseSigning = signingConfigs.create("distribution") {
  if (signingPath != null) {
   storeFile = file(signingPath)
   storePassword = providers.environmentVariable("UMUO_KEYSTORE_PASSWORD").orNull
   keyAlias = providers.environmentVariable("UMUO_KEY_ALIAS").orNull
   keyPassword = providers.environmentVariable("UMUO_KEY_PASSWORD").orNull
  }
 }
 buildTypes.named("release") { signingConfig = releaseSigning }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(libs.lifecycle.viewmodel)
 implementation(project(":ui-tv"))
 implementation(libs.activity.compose)
 implementation(platform(libs.compose.bom))
 implementation(libs.compose.ui)
 implementation(libs.compose.foundation)
 implementation(libs.compose.material3)
}

// 允许无密钥执行 debug/lint，但禁止意外输出未签名的发行 APK。
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
 doFirst {
  listOf("UMUO_KEYSTORE_PATH", "UMUO_KEYSTORE_PASSWORD", "UMUO_KEY_ALIAS", "UMUO_KEY_PASSWORD").forEach {
   require(!providers.environmentVariable(it).orNull.isNullOrBlank()) { "Release signing requires $it; see docs/RELEASING.md" }
  }
 }
}
