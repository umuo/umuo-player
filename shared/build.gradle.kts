plugins {
 alias(libs.plugins.kotlin.multiplatform)
 alias(libs.plugins.android.library)
 alias(libs.plugins.kotlin.serialization)
 alias(libs.plugins.sqldelight)
}
kotlin {
 androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
 jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
 if (providers.gradleProperty("enableAppleTargets").getOrElse("true").toBoolean()) {
  listOf(iosArm64(), iosSimulatorArm64(), tvosArm64(), tvosSimulatorArm64()).forEach {
   it.binaries.framework { baseName = "UmuoShared"; isStatic = true }
  }
 }
 sourceSets {
  commonMain.dependencies {
   implementation(libs.coroutines)
   implementation(libs.serialization)
   implementation(libs.ktor.core)
   implementation(libs.ktor.auth)
   implementation(libs.xmlutil)
   implementation(libs.sql.runtime)
  }
  commonTest.dependencies { implementation(kotlin("test")); implementation(libs.ktor.mock); implementation(libs.coroutines.test) }
  androidMain.dependencies { implementation(libs.ktor.okhttp); implementation(libs.sql.android) }
  jvmMain.dependencies { implementation(libs.ktor.cio); implementation(libs.sql.jvm) }
  jvmTest.dependencies { implementation(libs.sql.jvm) }
  findByName("appleMain")?.dependencies { implementation(libs.ktor.darwin); implementation(libs.sql.native) }
 }
}
android {
 namespace = "app.umuo.shared"
 compileSdk = 36
 defaultConfig { minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
sqldelight { databases { create("UmuoDatabase") { packageName.set("app.umuo.core.db") } } }
