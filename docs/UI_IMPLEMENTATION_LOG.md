# Joyboy UI Implementation Log

## v626 — Skin/Theme cleanup and manufacturer-inspired layouts

- **Baseline:** `Joyboy_v625.zip` (source snapshot; no `.git` metadata was present in the archive).
- **Files changed:** `TvSkin.kt`, `TvSkinClassic.kt`, `TvSkinBrand.kt` (new), `TvHome.kt`, `TvPresets.kt`, `Skin.kt`, `TvSkinHub.kt`, `TvSkinGlass.kt`, `TvSkinRail.kt`, `app/kt_manifest.txt`, `app/build.gradle.kts`, `dev-tests/check_tv_skins.sh` (new), `docs/TV_SKINS_PLAN.md`, `STATUS.txt`, `CHANGES_v626.md` and the UI continuity docs.
- **Reason:** Combined presets made the same structural Skin appear multiple times with different color/icon combinations and allowed some theme-only routes to bypass Skin selection.
- **Implementation:** One selectable entry per layout; added four distinct TV homepage header/navigation/card/focus treatments; set a full-width bound on the shared home header; moved normal TV theme-labelled routes to Skin-first selection; relabeled legacy app-wide animation profiles as “نمط الحركة”; removed the duplicate gallery from primary/quick menus; retained saved-combination tools via a separately named manager.
- **Migration:** `tv_skin=rail` maps to `hub`; saved presets and imported files referencing Rail map to Hub; `classic` remains the stable ID for Original S.
- **Tests:** `sh dev-tests/check_manifest.sh` passed. `sh dev-tests/check_tv_skins.sh` passed. Static source review performed.
- **Not performed:** Gradle build, installed-app test, TV remote test, visual screenshot comparison. Environment lacked Gradle/Android SDK and the uploaded ZIP contained no Gradle wrapper.
- **Known risks to validate:** Real screen sizing and focus traversal should be tested on Android TV hardware; brand-inspired layouts are original implementations rather than exact manufacturer recreations.
