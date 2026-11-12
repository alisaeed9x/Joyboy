# Joyboy Player UI/UX Blueprint — v939

## Product intent
MeeTube remains the unified playback surface on mobile. Joyboy owns the integration layer: source identity, translation controls, library history, browser return behavior, and (later) TV remote parity. The visual language must remain MeeTube's existing glass treatment rather than introducing a second player design system.

## Shared visual tokens
- Reuse MeeTube glass surfaces, existing `MtGlass`/`MtPlayerLook` colors, corner radii, blur and motion curves.
- Keep control hierarchy consistent: primary transport controls, timeline, secondary media actions, then contextual translation tools.
- Keep text labels readable and localized; icons alone are insufficient for uncommon actions such as subtitle translation or audio-track selection.
- Respect system insets, RTL/LTR, font scaling, and TV safe areas.
- Avoid full-screen blur or per-frame work on the UI thread. Prefer existing MeeTube animation primitives.

## Screen specifications

### Fullscreen player — mobile/tablet
- Video surface owns the visual focus; controls appear on the existing MeeTube visibility lifecycle.
- Primary row: play/pause, previous/next where available, seek affordances, and timeline.
- Secondary row/sheet: quality, audio track, speed, subtitle, translation, download, repeat and sleep timer.
- Joyboy translation pill and settings pill remain integrated in the existing options row; subtitle overlay margin follows control visibility.
- Source context is not shown as a disruptive banner. It is retained in session state and used for return/minimize routing.

### Browser-origin mini player
- For Joyboy Browser-origin media, use Android system PiP as the safe overlay while the Joyboy Browser remains underneath. This avoids routing into MeeTube's own Browse feed, which is not the originating page.
- PiP shows only the video and native playback actions. Expanding returns to the same MeeTube playback session.
- If the device refuses PiP, restore the fullscreen player and do not navigate to an unrelated feed.
- This is intentionally a source-specific fallback; a future in-app Browser card requires Browser to implement a proper `MiniHost` surface contract.

### Bottom navigation
- One reusable indicator follows the selected cell; no per-button duplicate capsules.
- The glass capsule must remain visible while the optional navigation lens animates. The lens may enhance the transition but must not be the only thing making the selected state visible.
- Recompute geometry after order changes, RTL layout, and width changes. Preserve the selected state after Activity transitions.

### Android TV player (planned, disabled)
- Do not ship the mobile touch overlay as the TV UI.
- Dedicated D-pad control strip with large focus targets, visible focus ring, stable focus restoration, and Back closing the topmost sheet before leaving playback.
- Numeric seek overlay accepts digits, confirms on OK, cancels on Back, and times out safely.
- Translation, subtitle, audio and quality sheets must be focusable and usable at viewing distance.
- New TV player remains behind `mt_tv_player=false` until physical TCL validation.

## Motion principles
- Preserve MeeTube's existing fullscreen morph, swipe-down and PiP transitions.
- Source routing is independent of animation completion; animation callbacks must never choose the destination.
- A cancelled or refused transition restores the player to a usable state.
- No second ExoPlayer instance is created during minimize/restore.
