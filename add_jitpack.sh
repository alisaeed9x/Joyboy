#!/bin/sh
# (v325) يضيف مستودع jitpack.io لـ settings.gradle.kts (مرة واحدة) — شغّله من جذر المشروع: sh add_jitpack.sh
F=settings.gradle.kts
[ -f "$F" ] || F=settings.gradle
if [ ! -f "$F" ]; then echo "مفيش settings.gradle.kts هنا"; exit 1; fi
if grep -q "jitpack.io" "$F"; then echo "jitpack موجود فعلًا ✅"; exit 0; fi
# بعد mavenCentral() جوه dependencyResolutionManagement
sed -i '/dependencyResolutionManagement/,/^}/ s#mavenCentral()#mavenCentral()\n        maven { url = uri("https://jitpack.io") }#' "$F"
if grep -q "jitpack.io" "$F"; then echo "اتضاف jitpack ✅"; else echo "⚠ ماقدرتش أضيفه تلقائي — ضيف السطر ده جوه dependencyResolutionManagement.repositories:"; echo '        maven { url = uri("https://jitpack.io") }'; fi
