# Keep this file in step with REQUIREMENTS.md → Release checks.

# JNI: assistant_jni.cpp binds these by name (Java_app_korvy_launcher_assistant_Native_*) and calls
# Native.Listener.onToken(String) back by name. R8 must not rename or remove them.
-keep class app.korvy.launcher.assistant.Native { native <methods>; }
-keep interface app.korvy.launcher.assistant.Native$Listener { *; }
-keepclassmembers class * implements app.korvy.launcher.assistant.Native$Listener { boolean onToken(java.lang.String); }

# sherpa-onnx's JNI reads its Kotlin config classes' fields by name and calls back into GeneratedAudio etc.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# ...and calls the audio callback we pass to generateWithCallback by its exact JNI signature
# invoke([F)Ljava/lang/Integer;. As a lambda, R8 merged it into a shared class without that method and the voice
# process aborted the moment speech started; it is a named class, kept whole.
-keep class app.korvy.launcher.assistant.AudioChunkCallback { *; }
# commons-compress references optional codecs (zstd, brotli, xz…) we don't use.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn io.airlift.compress.**
-dontwarn org.objectweb.asm.**
