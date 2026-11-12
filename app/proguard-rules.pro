# TVGuardian ProGuard rules
# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep Question data classes
-keepclassmembers class com.tvguardian.model.** { *; }
-keepclassmembers class com.tvguardian.data.WrongRecord { *; }

# Keep all model enums and serializable
-keepclassmembers class com.tvguardian.model.Question$* { *; }

# Keep serializable fields
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# NanoHTTPD — 内嵌 HTTP Server（手机远程控制）
-dontwarn fi.iki.elonen.**
-keep class fi.iki.elonen.** { *; }
-keep interface fi.iki.elonen.** { *; }
