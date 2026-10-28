#!/usr/bin/env bash
# (v620) فحص قبل الدفع: كل ملف .kt في الكود لازم يكون في app/kt_manifest.txt، وإلا pruneStaleKt بيمسحه وقت البناء
# (ده اللي كان بيخلّي LaunchSplashActivity تختفي من الـAPK والأيقونة الرئيسية تكرّش). تشغيل: bash dev-tests/check_manifest.sh
cd "$(dirname "$0")/.." || exit 1
bad=0
for f in app/src/main/java/com/tttt/subtitler/*.kt; do
  rel="com/tttt/subtitler/$(basename "$f")"
  grep -qxF "$rel" app/kt_manifest.txt || { echo "ناقص من kt_manifest.txt: $rel"; bad=1; }
done
[ $bad = 0 ] && echo "OK: كل الملفات في kt_manifest.txt" || exit 1
