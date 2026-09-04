# ProGuard rules for QQ NekoRewrite
# Xposed 模块通常不需要混淆，保留所有类和方法

-keep class com.neko.rewrite.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}