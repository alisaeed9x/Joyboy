-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# NewPipeExtractor (reflection + Rhino for signature decoding)
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
