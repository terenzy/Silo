# Keep Kotlinx Serialization models for IPC protocol with silod
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.silo.app.ipc.**$$serializer { *; }
-keepclassmembers class dev.silo.app.ipc.** {
    *** Companion;
}
-keepclasseswithmembers class dev.silo.app.ipc.** {
    kotlinx.serialization.KSerializer serializer(...);
}
