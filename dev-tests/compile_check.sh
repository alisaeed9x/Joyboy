#!/bin/sh
# فحص كومبايل لكل كود Android (ماعدا YtNp.kt اللي محتاج NewPipe) على android.jar + stubs media3 — بيكشف أخطاء التواقيع/الأنواع قبل Gradle.
# الاستخدام: sh dev-tests/compile_check.sh /path/to/android.jar   (android.jar من أي android-34/platforms)
# ⚠ مش بديل Gradle: stubs media3 مكتوبة من الذاكرة، فالتواقيع الحقيقية لسه بتتأكد في Actions.
set -e
J=${1:?usage: compile_check.sh android.jar}
K=app/src/main/java/com/vanilla/tvlauncher
FILES=$(ls $K/*.kt | grep -v '/YtNp.kt$')
out=$(mktemp -d)
kotlinc -cp "$J" $FILES dev-tests/media3-stubs/*.kt -d "$out" 2>&1 | grep -E "^(e:|.*error:)" && exit 1 || true
echo "compile_check: OK"
