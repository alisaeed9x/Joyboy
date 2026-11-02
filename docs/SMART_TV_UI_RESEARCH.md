# Smart TV UI Research Notes — Joyboy v6.26

## Purpose

These notes record the official references used to guide the new Joyboy manufacturer-inspired TV layouts. They describe broad layout and interaction patterns, not a claim of pixel-perfect replication. Interfaces vary by model, region, app availability, firmware, and OS generation.

## Official references reviewed

### Samsung Smart Hub / Tizen
- Samsung Smart Hub home-screen guide: https://www.samsung.com/sg/support/tv-audio-video/use-smart-hub-home-on-your-samsung-smart-tv/
- Samsung Egypt Smart TV / Tizen overview: https://www.samsung.com/eg/tvs/smart-tv/highlights/
- Observations: Smart Hub has changed between Tizen generations. Samsung documents left-side navigation and content categories such as recommendations, live content, and apps on relevant versions. The Joyboy `samsung` skin uses its own horizontal navigation strip and resume card as an original Smart Hub-inspired interpretation, not a claim that all Samsung versions use this exact layout.
- Joyboy implementation: wide horizontal navigation tiles, a horizontal continue-watching card, Joyboy-owned icons, current-theme colors, and the existing launcher rows retained beneath the header.

### LG webOS
- LG webOS TV home-screen developer guide: https://webostv.developer.lge.com/develop/guides/home-screen
- Observations: LG home-screen organization depends on webOS generation. Official documentation describes global navigation and content discovery, including hero/banner content, cards, apps, and recommendations in supported versions. Older webOS generations use a more prominent home/launcher bar.
- Joyboy implementation: a hero-style resume card paired with an informational card, compact navigation controls, and poster-style local folder cards. This is a Joyboy-authored webOS-inspired composition, not a proprietary webOS reproduction.

### TCL / Google TV
- TCL Google TV home-screen guide: https://support.tcl.com/en_US/what%E2%80%99s-on-the-google-tv-home-screen
- Observations: TCL products ship with different operating systems in different regions and generations. Google TV's tabs and available home content may vary by region, language, account, and device.
- Joyboy implementation: compact utility navigation and a resume/progress card that is structurally different from the Samsung horizontal card and LG hero composition. The skin remains based on Joyboy's local library and existing app shortcuts.

### Xiaomi / PatchWall
- Xiaomi PatchWall support article: https://www.mi.com/global/support/article/KA-124771/
- Observations: PatchWall is a content-discovery and recommendation interface; product capabilities and availability vary by device and market. Xiaomi TVs may also use Google TV or Android TV variants.
- Joyboy implementation: a side navigation rail paired with a local-content wall and poster-style folders. The skin does not claim to reproduce Xiaomi recommendation services or use Xiaomi artwork.

## Shared design principles applied

- Use clearly differentiated navigation structures and content compositions; do not create a separate skin for every color palette.
- Reuse the currently selected Joyboy Theme so Skin and Theme remain independent.
- Keep existing Android Views, app shortcuts, browser and YouTube entry points, local media, folder actions, history, playback, and remote behavior intact.
- Preserve each folder's open and long-press details actions; list view continues using the existing folder-card implementation.
- Use original Joyboy labels, symbols, layout code, and styling. Do not embed third-party logos, screenshots, copyrighted artwork, manufacturer firmware, or extracted source code.
- Treat this document as a reference summary. Recheck the official URLs when designing additional skins because TV interfaces can change over time.

## Validation boundary

This document records design research and implementation intent. It does not assert that Joyboy was compiled, run on a physical TV, or tested against every vendor-specific remote. Those checks must be performed separately on a configured Android build environment and target devices.
