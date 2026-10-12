# Native AI libraries use JNI. Keep their Java entry points if shrinking is enabled.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.schoolhelp.app.audio.SpeechProgress { *; }
