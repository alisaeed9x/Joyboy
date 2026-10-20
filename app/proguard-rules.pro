# Subtitler — R8 rules (v458, deliberately conservative)
# الهدف من أول تفعيل: تصغير المكتبات والموارد من غير ما نكسر الانعكاس/JNI.
# بعد اختبار إصدار كامل، ممكن تضييق قاعدة التطبيق تدريجيًا.

# كل كلاسات التطبيق (فيها نداءات بالانعكاس وJNI وخدمات في المانيفست)
-keep class com.tttt.subtitler.** { *; }
-keepclassmembers class com.tttt.subtitler.** { *; }

# JNI / native
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# NewPipeExtractor (انعكاس وخدمات جافا سكريبت)
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

# ML Kit / Google Play services
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Media3
-dontwarn androidx.media3.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Glide
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# انعكاس عام شائع
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class * implements android.os.Parcelable { *; }

# (v466) Rhino (org.mozilla.javascript) بيشاور على كلاسات مش موجودة على أندرويد — R8 كان بيوقع البناء
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
