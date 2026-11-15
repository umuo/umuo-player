-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization serializers and companions
-keepclassmembers class app.umuo.** {
    *** Companion;
}
-keepclasseswithmembers class app.umuo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.umuo.**$$serializer { *; }

# Ktor OkHttp engine and ServiceLoader discovery
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }

# XmlUtil ServiceLoader discovery
-keep class * implements nl.adaptivity.xmlutil.XmlStreamingFactory { *; }
-keep class nl.adaptivity.xmlutil.** { *; }

# Kotlin Coroutines ServiceLoader
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Desktop/optional JVM references pulled by Ktor/XmlUtil
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
