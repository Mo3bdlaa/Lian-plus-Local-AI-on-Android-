# JNI entry points are resolved by name from C++ — never rename or strip them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.lian.plus.llm.LlamaNative { *; }
-keep class com.lian.plus.image.SdNative { *; }
-keep class com.lian.plus.llm.LlamaNative$* { *; }
-keep class com.lian.plus.image.SdNative$* { *; }

# Classes instantiated from native callbacks.
-keep class com.lian.plus.llm.TokenSink { *; }
-keep class com.lian.plus.image.ProgressSink { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lian.plus.**$$serializer { *; }
-keepclassmembers class com.lian.plus.** { *** Companion; *** INSTANCE; kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
