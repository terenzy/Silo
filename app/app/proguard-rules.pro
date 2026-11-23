# Keep Kotlinx Serialization models & enums for IPC protocol with silod
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.silo.app.ipc.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class dev.silo.app.ipc.** { *; }
-keepclassmembers enum dev.silo.app.ipc.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keepclassmembers class dev.silo.app.ipc.** {
    *** Companion;
}
-keepclasseswithmembers class dev.silo.app.ipc.** {
    kotlinx.serialization.KSerializer serializer(...);
}

