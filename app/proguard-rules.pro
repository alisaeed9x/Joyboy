# Subtitler — R8 rules (v458, deliberately conservative)
# الهدف من أول تفعيل: تصغير المكتبات والموارد من غير ما نكسر الانعكاس/JNI.
# بعد اختبار إصدار كامل، ممكن تضييق قاعدة التطبيق تدريجيًا.

# كل كلاسات التطبيق (فيها نداءات بالانعكاس وJNI وخدمات في المانيفست)
# (v1181b) تقليص كود التطبيق نفسه: R8 بيشيل الكلاسات/الدوال اللي مفيش حد بيستدعيها، من غير تغيير أسماء (-dontobfuscate) عشان
# اللوج وأسماء الكلاسات (SharedPreferences/Intent/JNI/Reflection) تفضل زي ما هي. لو حاجة وقعت: ارجع السطرين القديمين
#   -keep class com.tttt.subtitler.** { *; }   و   -keepclassmembers class com.tttt.subtitler.** { *; }
-dontobfuscate
-keepclasseswithmembers class com.tttt.subtitler.** { native <methods>; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepclassmembers enum com.tttt.subtitler.** { *; }
-keep class * extends android.view.View { <init>(...); }
-keep class * extends android.app.Fragment { <init>(); }
-keep class * extends androidx.fragment.app.Fragment { <init>(); }
-keepclassmembers class * implements java.io.Serializable {
    private static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# JNI / native
-keepclasseswithmembernames class * { native <methods>; }


# Media3  (v1181: اتشال -keep الكامل — المكتبات جاية بقواعدها؛ لو الـHLS/DASH وقف: ارجع -keep class androidx.media3.exoplayer.** { *; })
-dontwarn androidx.media3.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Glide
-dontwarn com.bumptech.glide.**

# انعكاس عام شائع
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class * implements android.os.Parcelable { *; }

# (v466) Rhino (org.mozilla.javascript) بيشاور على كلاسات مش موجودة على أندرويد — R8 كان بيوقع البناء
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**

-dontwarn org.mozilla.**

# (v878) MeeTube — انعكاس على media3 والـpreferences؛ ومحرك JS (J2V8) و InnerTube
-keep class com.liskovsoft.** { *; }
-keep class com.tttt.subtitler.mtui.** { *; }   # (v1108 Y6a) الجلاس المنقول من :mobile — بيتعمل inflate من layout/joy_nav_glass.xml
-keep class com.github.vkay94.** { *; }
-keep class com.eclipsesource.v8.** { *; }
-dontwarn com.liskovsoft.**
-dontwarn org.chromium.net.**
-dontwarn org.conscrypt.**

# (v1029) dnsjava (جاي من MeeTube/sharedutils) بيشاور على JNA وWindows وJNDI وlombok — مش موجودين على أندرويد وفرع Windows عمره ما بيتنفّذ هنا.
# ده اللي كان موقّع R8 في v1028 (missing_rules.txt). dontwarn كفاية؛ مفيش حاجة منهم بتتنادى فعليًا.
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn lombok.**
-dontwarn org.xbill.DNS.config.**
-dontwarn org.slf4j.impl.**

# (v1103 Y2) NewPipeExtractor (رجع مع يوتيوب القديم) — بيشاور على Rhino/jsoup/protobuf اختياريين؛ timeago patterns بتتحمّل بالاسم
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.google.protobuf.**
-dontwarn jdk.dynalink.**
-dontwarn javax.annotation.**
# (v1103 Y1) RxJava3 — مستعمل من شاشة دخول الكود
-dontwarn io.reactivex.rxjava3.**
