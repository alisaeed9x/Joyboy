# Player Interaction Specification — v939

## Source identity
- The launch intent is the source of truth for origin metadata.
- Browser-origin streams carry `ret=browser`, original page URL (when known), Referer, User-Agent and Cookie. YouTube IDs opened by Browser retain the same return-origin metadata keyed by video ID.
- `MtWeb` associates metadata with the exact stream URI and exposes a minimize policy for that URI.
- The playback Activity must not infer the origin from whichever Activity resumed most recently.

## Minimize interactions
| Input | Browser-origin stream | Other sources |
|---|---|---|
| Fullscreen drag down | Enter Android system PiP | Existing MeeTube MiniHost handoff |
| Back minimize gesture | Same centralized drag/minimize path | Existing MeeTube behavior |
| Minimize control | Must use the same source policy when wired | Existing MeeTube behavior |
| PiP expand | Restore same MeeTube session | Native PiP restoration |
| PiP dismiss | Close only playback task; Browser task remains | Existing MeeTube dismiss policy |

If PiP entry is refused by the OEM, restore the full player and do not fall through to BrowseView.

## Bottom navigation indicator
- Selection state is rendered independently from the optional lens effect.
- While the lens runs, keep the indicator partially visible; the lens completion callback restores full opacity.
- If the lens is disabled or unsupported, use the normal indicator animation.
- Re-layout must recalculate the indicator position from the selected cell bounds.

## TV remote — acceptance contract (not yet implemented in v939)
- Preserve existing HTTP/RPC payload names and response keys.
- Commands must be idempotent where repeat delivery is possible.
- `close` must not be interpreted as natural end-of-media and must not trigger autoplay-next.
- Numeric seek: digit entry → visible buffer → OK commits → Back clears.
- D-pad focus must never be lost behind a hidden overlay.
- `mt_tv_player` default is `false`; legacy `Player.kt` remains the TV fallback.

## Regression cases
1. Browser page → open captured video → fullscreen → drag down: Browser is visible under PiP; no YouTube/BrowseView navigation.
2. Browser-origin video → PiP refused: fullscreen player is restored.
3. YouTube-origin video → minimize: existing MeeTube behavior is unchanged.
4. Local media → minimize: existing MeeTube MiniHost behavior is unchanged.
5. Bottom-nav lens enabled → tap YouTube/Select/other destinations: selected capsule remains visible during and after transition.
6. TV flag absent or false: legacy TV route remains active.
