# Proguard & R8 Optimization Rules for Bingo Multiplayer

# ── General Android & Kotlin Keep Rules ──
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable, EnclosingMethod
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**

# ── Explicit Keep Annotations ──
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ── Domain Models and Network Payloads ──
-keep class com.bingo.multiplayer.domain.model.** { *; }
-keepclassmembers class com.bingo.multiplayer.domain.model.** { *; }

-keep class com.bingo.multiplayer.domain.network.** { *; }
-keepclassmembers class com.bingo.multiplayer.domain.network.** { *; }

-keep class com.bingo.multiplayer.domain.repository.** { *; }
-keepclassmembers class com.bingo.multiplayer.domain.repository.** { *; }

# ── Kotlinx Serialization ──
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.SerializationConstructorMarker
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer();
}
-keepclassmembers class **$$serializer {
    public static **$$serializer INSTANCE;
}
-keep class kotlinx.serialization.** { *; }

# ── Kotlin Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Eclipse Paho MQTT ──
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# ── OkHttp3 ──
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Google Play Services Auth ──
-keep class com.google.android.gms.auth.api.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**
