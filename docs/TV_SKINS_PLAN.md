# Joyboy TV — Skin & Theme System (Current Plan)

## Permanent distinction

- **Skin**: a TV interface layout and interaction design. It controls navigation placement, content hierarchy, header/toolbar structure, folder-card composition, navigation glyphs, and focus behavior.
- **Theme**: a visual palette/style applied to the selected Skin. It includes light/dark appearance, colors, surfaces, borders, typography, shadows, glass-like treatments, and compatible visual effects.
- **Icon Pack**: the user's independent choice for application icons. A Skin may provide its own navigation glyph style, but switching Skin must not silently overwrite the user's selected application icon pack.
- **Motion style**: the legacy app-wide animation/rounding/sound option (`Skins` in code). Its visible label is now “نمط الحركة” so it is not confused with TV layout Skins.

## Selectable TV skins

The Skin & Theme Studio contains exactly seven entries in this order:

| Skin ID | Display name | Layout identity |
|---|---|---|
| `hub` | Hub (شكل Google TV) | Existing content-row home, rounder cards, LIFT focus and a continuation banner |
| `classic` | Original S | Existing classic layout; the stable `classic` ID is retained for settings and saved presets |
| `glass` | Glass (شكل OriginOS) | Existing translucent surfaces, GLOW focus, and stacked folder posters |
| `samsung` | Samsung Smart Hub | Wider horizontal navigation strip, horizontal resume card, RING focus |
| `lg` | LG webOS | Large poster/hero presentation, compact home bar, rounder poster cards and GLOW focus |
| `tcl` | TCL / Google TV | Compact quick-navigation strip, progress-led resume card, poster-style folder cards and LIFT focus |
| `xiaomi` | Xiaomi / PatchWall | Side navigation rail, content-wall hierarchy, poster cards and UNDERLINE focus |

Manufacturer-inspired skins use original Joyboy components and broad visual/interaction concepts; they do not ship manufacturer artwork or claim to be exact copies. They all preserve the existing embedded launcher content rows and media logic. The new layouts customize the shared home header, navigation, folder cards, focus tokens, and navigation glyph language instead of replacing the home data renderer.

## Themes

All existing Theme IDs remain. The Theme picker includes the existing Light, Dark/AMOLED, Windows 11 (light/dark), Windows 7/Aero, and other existing styles. Do not duplicate these as Skin entries. `themeIds` on each Skin only determines recommended ordering; it does not delete or block existing compatible Themes.

## One normal customization flow

**Appearance → Skin & Theme Studio → Choose Skin → Choose Theme.**

- `TvPage.LAYOUT`, legacy `TvPage.THEME`, and legacy `TvPage.PRESETS` route to the Skin-first selector.
- The selector changes `tv_skin` and `theme` independently and preserves stable IDs.
- The current Theme is marked in the theme list, including when a different Skin is being selected.
- Duplicate preset-gallery cards were removed from the primary appearance page and Hub/Glass quick menus.
- Saved presets are not presented as additional Skins. To preserve import/export, save-current, and color-remote-key bindings, **Appearance → Tools → Saved Combinations** opens the separate manager. `TvPage.PRESET_MANAGER` is reserved for that management surface.

## Backward compatibility and safety

- `tv_skin=classic` remains valid and now displays as **Original S**.
- A saved `tv_skin=rail` safely migrates to `hub`, because Rail is no longer a selectable option.
- Existing saved combinations using Rail are migrated to Hub, including old imported preset files.
- Keep media library, playback history, favorites, folders, playback controls, external-app boundaries, and D-pad behavior intact.
- `TvSkinBrand.kt` must remain in `app/kt_manifest.txt` or `pruneStaleKt` may delete it.

## Validation status

- `dev-tests/check_tv_skins.sh`: static regression checks for registry, Theme IDs, routing, migration, layout hooks, and manifest.
- `dev-tests/check_manifest.sh`: manifest validation.
- Android Gradle build, visual review, and remote navigation testing have **not** been run in this environment because the uploaded ZIP has no wrapper and no configured Gradle/Android SDK is available here.

## Session continuity

Before continuing, read `UI_MASTER_SPEC.md`, `UI_PROJECT_STATE.md`, `UI_IMPLEMENTATION_LOG.md`, and `UI_TEST_MATRIX.md`. Verify the actual source state before assuming previous work is complete. Append a log entry and update the state after each meaningful change.
