# Joyboy TV — Permanent UI Customization Specification

## Source of truth
This specification is supported by `docs/SMART_TV_UI_RESEARCH.md`, which records the official interface references used for the manufacturer-inspired layouts.

This specification defines the permanent meaning of Skin and Theme for Joyboy's Android TV interface. Do not use conversational memory as a substitute for these files.

## Skin
A **Skin** is an interface layout and interaction design: navigation structure and location, information hierarchy, content organization, card layout, menu placement, and layout-specific focus treatment. One skin must not be duplicated into separate light/dark/Windows variants.

Current selectable TV skins:
1. `hub` — Hub / Google TV-inspired.
2. `classic` — Original S; this is the stable legacy ID for the original interface.
3. `glass` — Glass / OriginOS-inspired.
4. `samsung` — Samsung Smart Hub-inspired layout.
5. `lg` — LG webOS-inspired layout.
6. `tcl` — TCL / Google TV-inspired layout.
7. `xiaomi` — Xiaomi / PatchWall-inspired layout.

The experimental `rail` source file remains for compatibility/history but is not shown as a selectable skin. A saved `rail` preference migrates to `hub`.

## Theme
A **Theme** is visual styling applied to the chosen Skin, including colors, background/surface colors, typography, borders, shadows, and related appearance tokens. Existing Theme IDs remain available; do not delete or rename them without a separate migration plan.

## Required selection order
One appearance entry point: **Skin & Theme Studio / السكنات والسمات**.

Flow: **Choose Skin → Choose Theme → Apply**.

All normal TV menu entries that say Theme(s), `TvPage.THEME`, and legacy `TvPage.PRESETS` must redirect into the Skin-first flow rather than opening a theme-only screen. Never present a combined skin/theme/icon-pack preset as a selectable Skin. The separate `TvPage.PRESET_MANAGER` entry is reserved for saved-combination management (save/import/export/color-key bindings), not ordinary Skin/Theme selection.

## Preservation requirements
- Keep `tv_skin` IDs stable when possible; `classic` remains the ID for Original S.
- Store `tv_skin` and `theme` independently.
- Skin changes must not reset Theme; Theme changes must not reset Skin.
- Preserve media library, playback history, favorites, remote controls, and external-app boundaries.
- Keep the user-selected Icon Pack independent when switching Skin; Skin navigation glyphs may have a different style.
- The legacy app-wide `Skins` setting is a motion/interaction profile; its UI label must say “نمط الحركة” so it is not confused with `TvSkins` or Theme.
- Do not introduce Jetpack Compose or replace the existing Android Views implementation without explicit authorization.
- All newly added Kotlin files must be in `app/kt_manifest.txt` due to `pruneStaleKt`.
- Do not claim a build, runtime, animation, or remote test passed unless it was actually run.

## Continuity protocol
Before every new session: read `UI_MASTER_SPEC.md`, `UI_PROJECT_STATE.md`, latest `UI_IMPLEMENTATION_LOG.md` entries, and `UI_TEST_MATRIX.md`; inspect branch/commit and working-tree changes; continue from verified state; update state and log after each milestone.
