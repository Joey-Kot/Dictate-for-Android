# Exception type names are part of the redacted network diagnostics. Keep
# these names useful in release builds without retaining arbitrary messages.
-keepnames class com.joeykot.dictate.advanced_audio.** extends java.lang.Throwable
-keepnames class okhttp3.** extends java.lang.Throwable

# The VAD entry point uses JNI name-based lookup.
-keep class com.joeykot.dictate.audio.NativeVad { native <methods>; }
