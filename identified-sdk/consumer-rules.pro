# Parcelable and Kotlin serialization generate their own rules. Preserve the
# bundled ML Kit component registrar discovered by name at startup.
-keep class com.google.mlkit.**.FaceRegistrar { *; }
-keep class com.google.mlkit.vision.face.internal.FaceRegistrar { *; }
