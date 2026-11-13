#!/usr/bin/env bash
# (v620/v776) فحص قبل الدفع: كل ملف .kt في الكود (بما فيه المجلدات الفرعية زي phonehome/) لازم يكون في app/kt_manifest.txt،
# وإلا pruneStaleKt بيمسحه وقت البناء (ده اللي كسر بيلد v775: HomeThemes.kt ناقص ← Unresolved reference 'HomeThemeStore').
# تشغيل: bash dev-tests/check_manifest.sh
cd "$(dirname "$0")/.." || exit 1
bad=0
base=app/src/main/java
while IFS= read -r f; do
  rel="${f#$base/}"
  grep -qxF "$rel" app/kt_manifest.txt || { echo "ناقص من kt_manifest.txt: $rel"; bad=1; }
done < <(find "$base" -name '*.kt' | sort)
# والعكس: سطر في المانيفست مالوش ملف
while IFS= read -r rel; do
  [ -z "$rel" ] && continue
  [ -f "$base/$rel" ] || { echo "في المانيفست ومالوش ملف: $rel"; bad=1; }
done < app/kt_manifest.txt
[ $bad = 0 ] && echo "OK: كل الملفات في kt_manifest.txt" || exit 1
