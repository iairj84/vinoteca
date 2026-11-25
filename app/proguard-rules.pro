# Names are kept so serialized JSON keys and any stack traces stay meaningful.
-dontobfuscate
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, EnclosingMethod

# kotlinx.serialization: generated serializers and their factories.
-keepclassmembers class com.vinoteca.app.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.vinoteca.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vinoteca.app.model.**$$serializer { *; }
-keepclassmembers class com.vinoteca.app.model.** {
    <fields>;
}

# OkHttp / coroutines / serialization optional references.
-dontwarn kotlinx.serialization.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**