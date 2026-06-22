# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# Keep line numbers in stack traces (deobfuscated via the uploaded mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── App classes: API models are (de)serialized by Gson by field name, so keep
#    them intact to avoid silent JSON breakage. (Safety over app-code obfuscation.)
-keep class ru.fsociety.vpn.** { *; }

# ── Gson ──
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses, Exceptions
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Retrofit / OkHttp / Okio ──
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ── sing-box / libbox (gomobile JNI) ──
-keep class io.nekohasekai.** { *; }
-keep class go.** { *; }
-dontwarn io.nekohasekai.**
-dontwarn go.**

# ── AmneziaWG / WireGuard ──
-keep class org.amnezia.** { *; }
-keep class com.zaneschepke.** { *; }
-dontwarn org.amnezia.**

-dontwarn kotlinx.coroutines.**

# ── Gson generic types / adapters (prevent null fields under R8) ──
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation class * implements com.google.gson.TypeAdapterFactory
-keep,allowobfuscation class * implements com.google.gson.JsonSerializer
-keep,allowobfuscation class * implements com.google.gson.JsonDeserializer
-keepclassmembers enum * { *; }

# ── Retrofit suspend functions / Kotlin metadata ──
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── OpenVPN3 / ics-openvpn (SWIG JNI bridge — called from native C++, invisible to R8) ──
-keep class net.openvpn.ovpn3.** { *; }
-keepclassmembers class net.openvpn.ovpn3.** { *; }
-dontwarn net.openvpn.ovpn3.**
-keep class net.openvpn.unified.** { *; }
-keepclassmembers class net.openvpn.unified.** { *; }
-dontwarn net.openvpn.unified.**