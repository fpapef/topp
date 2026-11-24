# MediaPipe passe par JNI et protobuf : on garde ses classes telles quelles.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
