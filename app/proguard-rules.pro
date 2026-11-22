-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class pe.bovedasegura.**$$serializer { *; }
-keepclassmembers class pe.bovedasegura.** {
    *** Companion;
}
-keepclasseswithmembers class pe.bovedasegura.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# zxing embedded
-keep class com.journeyapps.barcodescanner.** { *; }

# No logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
