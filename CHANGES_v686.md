# v686 — إصلاح فشل الـbuild بتاع v685
- EngineBackend: أضفت markAllDone(lastEnd) في الواجهة (Player.kt بيكلّم المحرك من خلالها مش Engine مباشرة)، وLocalEngine بتفوّضها لـ Engine.markAllDone.
- RemoteEngine: بتبعت أمر engine.markAllDone للتلفزيون، والتلفزيون بينفّذه في دسباتشر Player.kt.
- SubPick.kt (من v684) لازم يكون في الريبو في app/src/main/java/com/tttt/subtitler/ — الـCI كان مش لاقيه (Unresolved reference 'SubPick' في PlayerPad.kt).
- تحذير الـC++ بتاع armv8.2-a+dotprod+fp16 (whisper/ggml) مش بيوقّف الـbuild.
- ما اتكومبايلش هنا ولا اتجرّب على جهاز.
