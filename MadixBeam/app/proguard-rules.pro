# Madix Beam release rules.

# kotlinx.serialization: keep generated serializers of the history model.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class com.madix.beam.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.madix.beam.**$$serializer { *; }

# ZXing is used only for QR encoding; nothing is accessed reflectively.
-dontwarn com.google.zxing.**

# Strip verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
