# Joyboy UI Project State

- Project snapshot audited: `Joyboy_v625.zip`.
- Working target/version: 626 / 6.26.
- Main target: Android TV UI; existing Android Views architecture preserved.
- Skin model: independent `TvSkins` registry and `Themes` registry.
- Current selectable skin order: Hub, Original S (`classic` ID), Glass, Samsung Smart Hub, LG webOS, TCL / Google TV, Xiaomi / PatchWall.
- Current selection workflow: `TvHome.lookDialog()` → `skinThemePicker()`; `TvPage.THEME`, `TvPage.LAYOUT`, and `TvPage.PRESETS` route into the Skin-first flow.
- Main appearance page no longer exposes a duplicate preset gallery beside the regular selector. Hub and Glass quick menus show one Skin & Theme entry.
- Saved-combination operations remain available under Appearance → Tools → Saved Combinations; `TvPage.PRESETS` is the legacy Skin-first route and `TvPage.PRESET_MANAGER` opens the manager.
- `TvPresets` source/data remain present. Stored Rail selections, saved combinations, and old imported presets migrate to Hub.
- Legacy app-wide animation profiles are labeled “نمط الحركة”, separate from the TV layout Skin system.

## Changes made in v626 worktree
- Added `app/src/main/java/com/tttt/subtitler/TvSkinBrand.kt` with Samsung-, LG-, TCL-, and Xiaomi/PatchWall-inspired TV homepage layouts. Each uses current Theme colors, a distinct navigation glyph/layout treatment, folder cards, and focus tokens. Existing launcher rows remain rendered by the original home implementation.
- Renamed the visible legacy skin label from Classic to Original S without changing the persistent ID `classic`.
- Simplified skin registry to one entry per selectable layout; no Light/Dark variants are registered as skins.
- Redirected old theme-only entry routes to the shared Skin-first picker.
- Removed duplicate appearance and quick-menu entries for the preset gallery.
- Added the new file to `app/kt_manifest.txt`; updated version to 626 / 6.26.

## Verification status
- `sh dev-tests/check_manifest.sh` passed.
- `sh dev-tests/check_tv_skins.sh` passed, checking the seven-item registry, stable IDs, Windows Themes, Skin-first routes, saved-preset access, header width, and manifest.
- Source-level review performed for the changed routing, stable IDs, and layout structure.
- Gradle and Android SDK are not installed/configured in the current environment and the ZIP contains no Gradle wrapper. No APK build or real remote/device test has been run.

## Next step
Run the project's GitHub Actions build and JVM tests. Then validate all seven skins using a TV remote: D-pad, OK, Back, skin selection, Theme selection, app restart persistence, folder opening, recent playback, notifications, and settings.

## Research reference
- `docs/SMART_TV_UI_RESEARCH.md` records the official manufacturer references and explains which general patterns informed the Joyboy-inspired layouts.
