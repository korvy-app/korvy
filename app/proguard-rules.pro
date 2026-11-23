# Keep this file in step with REQUIREMENTS.md → Release checks.

# JNI: assistant_jni.cpp binds these by name (Java_com_hiddenoutlier_launcher_assistant_Native_*) and calls
# Native.Listener.onToken(String) back by name. R8 must not rename or remove them.
-keep class com.hiddenoutlier.launcher.assistant.Native { native <methods>; }
-keep interface com.hiddenoutlier.launcher.assistant.Native$Listener { *; }
-keepclassmembers class * implements com.hiddenoutlier.launcher.assistant.Native$Listener { boolean onToken(java.lang.String); }
