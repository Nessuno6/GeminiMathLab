# Preserve metadata & annotations required for R8 optimization and reflection-based libraries
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ONNX Runtime Mobile (native JNI callbacks into Java wrappers)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Anthropic Java SDK + transitive Jackson / OkHttp / JSON schema / Kotlin reflection helpers
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn java.lang.invoke.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn kotlin.reflect.**

# ML Kit GenAI Prompt & Digital Ink + Play Services Auth/Nearby
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.inject.**

# Room 2.2.5 & WorkManager 2.7.0 (transitive via ML Kit Digital Ink):
# bundled consumer rules omit `<init>()`, so R8 full mode strips WorkDatabase_Impl.<init>()
# and crashes androidx.startup.InitializationProvider on launch.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class * extends androidx.work.InputMerger {
    <init>();
}

# Firebase Components 16.1.0 (transitive via ML Kit):
# bundled consumer rule omits `<init>()`, stripping ComponentRegistrar constructors in R8 full mode.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
