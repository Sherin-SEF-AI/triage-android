# TRIAGE ProGuard rules

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ai.deepmost.triage.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.deepmost.triage.**$$serializer { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }

# LiteRT / TF Lite
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Keep model classes used reflectively via serialization
-keep class ai.deepmost.triage.**.model.** { *; }
