#!/usr/bin/env bash
# (v630) فحص ساكن: كل سكن لازم يرسم تطبيقاته بشكل مختلف عن التاني (مش نفس دواير الأوريجنال)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
F="$ROOT/app/src/main/java/com/tttt/subtitler/TvSkinHomes.kt"
fail() { echo "FAIL: $1" >&2; exit 1; }
for k in APP_CIRCLE APP_BANNER APP_SQUARE APP_DOCK APP_BIG APP_GRID; do
  [ "$(grep -c "appRow(h, t, s, $k)\|appGrid(h, t, s, $k\|appTile(h, t, it, $k)" "$F")" -ge 1 ] || fail "شكل التطبيقات $k مش مستخدم"
done
for k in HERO_GTV HERO_ANDROID HERO_TIZEN HERO_WEBOS HERO_TVOS; do
  grep -q "hero(h, t, $k)" "$F" || fail "Hero $k مش مستخدم"
done
grep -q 'appItems' "$ROOT/app/src/main/java/com/tttt/subtitler/TvSkin.kt" || fail "appItems ناقص من TvHomeSections"
grep -q 'homeAppItems' "$ROOT/app/src/main/java/com/tttt/subtitler/TvHome.kt" || fail "TvHome مابيبنيش appItems"
echo "PASS: 6 أشكال تطبيقات و5 أشكال Hero مستخدمة، والبيانات الخام موصّلة"
