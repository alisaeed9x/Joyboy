#!/usr/bin/env bash
# Static regression checks for v626 Skin/Theme routing and migration.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIN="$ROOT/app/src/main/java/com/tttt/subtitler/TvSkin.kt"
HOME="$ROOT/app/src/main/java/com/tttt/subtitler/TvHome.kt"
CLASSIC="$ROOT/app/src/main/java/com/tttt/subtitler/TvSkinClassic.kt"
BRAND="$ROOT/app/src/main/java/com/tttt/subtitler/TvSkinBrand.kt"
PRESETS="$ROOT/app/src/main/java/com/tttt/subtitler/TvPresets.kt"
THEME="$ROOT/app/src/main/java/com/tttt/subtitler/Theme.kt"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

# Registry: only the three existing base skins plus the four requested manufacturer layouts.
registry="$(sed -n '/private val list = ArrayList<TvSkin>().apply {/,/^    }/p' "$SKIN")"
for entry in 'TvSkinHub' 'TvSkinClassic' 'TvSkinGlass' 'TvSkinSamsung' 'TvSkinLg' 'TvSkinTcl' 'TvSkinXiaomi'; do
  grep -Fq "add($entry)" <<<"$registry" || fail "registry entry $entry missing"
done
grep -Fq 'add(TvSkinRail)' <<<"$registry" && fail 'retired Rail layout is still selectable'
[[ "$(grep -o 'add(TvSkin' <<<"$registry" | wc -l)" -eq 7 ]] || fail 'expected exactly seven selectable skins'
pass '7 unique TV skins registered (Hub, Original S, Glass, Samsung, LG, TCL, Xiaomi)'

grep -Fq 'override val id = "classic"' "$CLASSIC" || fail 'legacy classic ID changed'
grep -Fq 'override val name = "Original S"' "$CLASSIC" || fail 'visible Original S name missing'
grep -Fq 'storedId == "rail"' "$SKIN" || fail 'saved Rail migration missing'
grep -Fq 'storedSkin == "rail"' "$PRESETS" || fail 'saved preset Rail migration missing'
grep -Fq 'val sk = if (storedSkin == "rail") "hub" else storedSkin' "$PRESETS" || fail 'legacy Rail import mapping missing'
pass 'stable Original S ID and Rail preference/preset migration present'

for spec in 'TvSkinSamsung|"samsung"|"Samsung Smart Hub"' 'TvSkinLg|"lg"|"LG webOS"' 'TvSkinTcl|"tcl"|"TCL / Google TV"' 'TvSkinXiaomi|"xiaomi"|"Xiaomi / PatchWall"'; do
  IFS='|' read -r obj sid nm <<<"$spec"
  grep -Fq "object $obj" "$BRAND" || fail "$obj missing"
  grep -Fq "id = $sid" "$BRAND" || fail "$obj ID missing"
  grep -Fq "name = $nm" "$BRAND" || fail "$obj visible name missing"
done
pass 'four manufacturer-inspired skins have stable identifiers and names'

for themeId in 'Theme("win11"' 'Theme("win7"'; do
  grep -Fq "$themeId" "$THEME" || fail "existing Theme $themeId missing"
done
pass 'Windows 11 and Windows 7 remain Themes, not Skin entries'

for route in 'TvPage.THEME -> this@TvActivity.lookDialog()' 'TvPage.LAYOUT -> this@TvActivity.lookDialog()' 'TvPage.PRESETS -> this@TvActivity.lookDialog()' 'TvPage.PRESET_MANAGER -> TvPresets.show(skinHost)'; do
  grep -Fq "$route" "$HOME" || fail "route missing: $route"
done
grep -Fq 'Opt("🧩 السكنات والسمات"' "$HOME" || fail 'unified Skin & Theme Studio entry missing'
if grep -Fq 'معرض الثيمات' "$HOME"; then fail 'duplicate theme-gallery entry remains in the main appearance screen'; fi
grep -Fq 'Opt("🧰 التركيبات المحفوظة"' "$HOME" || fail 'saved preset / color-key management entry missing'
grep -Fq 'private fun themeDialog() { lookDialog() }' "$HOME" || fail 'legacy Theme-only dialog is not redirected to Skin-first flow'
grep -Fq 'fun themes(h: TvSkinHost): Boolean { h.open(TvPage.LAYOUT); return true }' "$SKIN" || fail 'default legacy Themes route is not Skin-first'
pass 'Skin-first routing is unified and saved-combination management remains separately accessible'

grep -Fq 'hh.addView(v, LinearLayout.LayoutParams(-1, -2))' "$HOME" || fail 'home header width constraint missing'
grep -Fq 'fun home(h: TvSkinHost, focus: Boolean, reuse: Boolean): Boolean = false' "$BRAND" || fail 'manufacturer skin must preserve embedded launcher rows'
grep -Fq 'h.open(TvPage.LAYOUT)' "$BRAND" || fail 'brand navigation does not use Skin-first route'
grep -Fq 'TvFolderCard.details(f, h.isSdFolder(f), f.videos.size)' "$BRAND" || fail 'folder details callback missing'
pass 'home header constraints, Skin-first nav, and folder interaction hooks present'

sh "$ROOT/dev-tests/check_manifest.sh"
pass 'new Kotlin source is included in kt_manifest.txt'
