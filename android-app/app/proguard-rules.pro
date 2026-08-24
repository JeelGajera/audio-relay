# R8 rules for the release build.
#
# Minification and resource shrinking are enabled (see app/build.gradle.kts),
# which matters most for kotlinx.serialization: its generated serializers are
# reached reflectively through synthetic `$$serializer` classes and `Companion`
# objects that R8 cannot see being used, and stripping them turns every
# control-channel message into a runtime crash rather than a build error.

-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault

# --- kotlinx.serialization ---
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# This app's own @Serializable types — ControlMessage and its subclasses.
-keep,includedescriptorclasses class com.audiorelay.app.**$$serializer { *; }
-keepclassmembers class com.audiorelay.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.audiorelay.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Sealed-class subclasses are resolved by their @SerialName discriminator at
# runtime, so their names must survive.
-keep @kotlinx.serialization.Serializable class com.audiorelay.app.** { *; }

# --- Framework entry points ---
# Instantiated by name from AndroidManifest.xml.
-keep class com.audiorelay.app.service.RelayService { *; }
-keep class com.audiorelay.app.ui.MainActivity { *; }

# Silences a warning about an optional dependency kotlinx.coroutines
# references but does not require at runtime.
-dontwarn java.lang.instrument.**
