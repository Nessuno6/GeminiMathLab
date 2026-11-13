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
