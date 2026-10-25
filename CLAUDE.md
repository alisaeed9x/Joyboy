# android-tv-launcher

Debloat scripts + a custom Apple TV-style home screen for Android TV / Google TV. No root; everything over ADB on the local network. README.md has the user-facing steps; this file is for coding agents.

## Layout
- `debloat/debloat.sh <tv-ip> [list]` hides packages with `pm uninstall -k --user 0` (reversible). `restore.sh <tv-ip> [pkg]` uses `cmd package install-existing`; with no package it restores the whole list and re-enables `com.google.android.tvlauncher`. Lists: one package per line, `#` comments. `adb shell -n` is required inside the `while read` loops, or adb swallows the rest of the list from stdin.
- `launcher/`: Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material`), `androidx.palette`. AGP 8.10 / Gradle 8.11.1 / Kotlin 2.0.21, JDK 17+, minSdk 28. No other dependencies (HTTP via HttpURLConnection, JSON via org.json).
  - `Apps.kt`: LEANBACK apps, banners, glow colour; favourites in SharedPreferences (`launcher`/`favourites`, seeded from `DEFAULT_FAVOURITES`); recents via UsageStatsManager (screensaver excluded).
  - `HomeScreen.kt`: header, trending hero, favourites (240x135) / Inputs / Recent / All apps shelves (200x112), long-press menu (Move, add/remove favourite, App info). Move mode: left/right, OK or Back finishes; the finishing key-up is swallowed or it launches the app. `OLED_CARE` gates idle/night dimming and pixel shift.
  - `Trending.kt`: TMDB `/trending/{feed}/week`, 8 titles, refreshed every 6h, retried every 15s until the first success. Config from `local.properties` → BuildConfig: `tmdb.key` (v3 key or v4 token), `tmdb.region` (default: device country), `tmdb.feed`. Button = first installed app in `PROVIDER_APPS` that streams it in the region, else `stremio:///detail/{movie|series}/{imdb}`. Backdrops kept as JPEG bytes; only previous/current/next decoded. Dark TMDB logos are rejected in favour of text.
  - `Glass.kt`: fake Liquid Glass (no RenderEffect before API 31). `Modifier.glass(shape)` draws a pre-blurred `GlassSource` lined up via positionInRoot. Home source = `TrendingTitle.frosted` (the backdrop blurred with Backdrop()'s masks baked in; keep `frostedBackdrop()` and `Backdrop()` in sync). Menu source = `captureBlurredWindow()` (PixelCopy at 160px + box blur).
  - `Inputs.kt`: HDMI inputs as `TvApp(isInput = true)`; CEC child inputs give the device name; hidden unless connected.
  - `Weather.kt`: last network location + Open-Meteo (no key). `Http.kt`: shared GET helpers. `Fonts.kt`: Inter variable font, `UiText` (opsz 14, default) / `UiDisplay` (opsz 32, for 20sp+).
  - `MainActivity.kt`: HOME activity; reloads on package changes; Home re-focuses the first favourite; Back swallowed. `launchFresh` kills the target's background process first (Live TV apps and Stremio ignore new inputs/links while running).

## Design rules (tvOS-like)
No row containers; rows bleed to the screen edge (`EdgePadding` as content padding). Focus = scale + app-colour glow + light rim + glare, never a thick border. Sentence-case type, weight not letter-spacing. Pills are glass; focused = solid white. The backdrop fades and parallaxes as the page scrolls.

## Build & deploy
```
cd launcher && cp local.properties.example local.properties   # sdk.dir + optional tmdb.*
./gradlew assembleRelease
S=<tv-ip>:5555
adb -s $S install -r app/build/outputs/apk/release/app-release.apk
adb -s $S shell pm grant me.prince.launcher android.permission.ACCESS_COARSE_LOCATION
adb -s $S shell appops set me.prince.launcher GET_USAGE_STATS allow
adb -s $S shell cmd package compile -m speed -f me.prince.launcher
adb -s $S shell cmd package set-home-activity me.prince.launcher/.MainActivity
```
Always ship release (R8); debug Compose is too slow on TV hardware.

## Rules when working on a user's TV
- Connecting: classic network ADB is `<ip>:5555`; TVs with "Wireless debugging" (Google TV, Android 11+) need `adb pair <ip>:<pairing-port>` with the on-screen code, then `adb connect <ip>:<port>`. The scripts accept `<ip>` or `<ip>:<port>`.
- Ask before removing any package. Never remove Live TV, `com.mediatek.*` / other chip services, the remote's Bluetooth service, `com.android.tv.settings`, Play Services, the keyboard or setup wizards.
- Disable (never uninstall) the stock launcher, and only after the custom launcher is installed and verified, otherwise Home shows a black screen. Fallback: `pm enable com.google.android.tvlauncher`. Warn the user that this can break hands-free "Ok Google" (README → Gotchas).
- Never commit or print the user's TMDB key; it belongs in `local.properties` only.
- Test by screenshotting: `adb shell screencap -p /sdcard/s.png` then `adb pull` (some TVs print vendor log lines into `exec-out` output, corrupting the PNG). Protected video and HDMI inputs capture as black.
- Before sending key events (`input keyevent`), check `dumpsys activity activities | grep mResumedActivity`: if a video app or TV input is in front, someone may be watching.

## Gotchas already handled in code
- Compose for TV pins the focused item 30% from the edge, scrolling the header off-screen; `minimalScroll()` (a custom BringIntoViewSpec) only scrolls when an item would leave the screen.
- On cold start the first tile isn't attached for a few frames; focus is requested with a short retry loop.
- The Wi-Fi name needs ACCESS_COARSE_LOCATION; VPN ad blockers make the VPN the active network, so the label checks all networks.
- The first TLS handshake after start sometimes fails (seen with AdGuard's HTTPS filtering); trending retries every 15s.

## Phone remote, push update, player (added from Subtitler)
- `LinkHttp.kt` (MiniHttp) + `TvLink.kt`: same wire protocol as Subtitler's TV side (HTTP :18770, UDP discovery :18771, `x-token` auth), so the Subtitler phone app controls this launcher unchanged. Started from `MainActivity.onCreate`. First phone pairs with no code; later phones need the 5-digit code shown as a toast on the TV. Keys are dispatched to our own foreground screen only (no accessibility service, so no control of other apps, no touchpad). `/music`, `/keys`, YouTube links are stubbed.
- `TvUpdate.kt`: `/offer` must carry `pkg == me.prince.launcher` (phone side: "📦 ابعت APK للتلفزيون" in Subtitler v540). Downloads over http (cleartext allowed in the manifest), then PackageInstaller session; first time needs "install unknown apps" for the launcher. Always bump `versionCode` or the TV answers `not_newer` (the phone's push is forced, so same-version reinstall is allowed).
- `PlayerActivity.kt`: libVLC (`org.videolan.android:libvlc-all`). Only video + subtitles on screen; `remoteState()` / `remoteAct()` mirror Subtitler's player JSON so the phone's player screen works. Opens `ACTION_VIEW video/*`, and `/open` links. Resume position per URI.
- `VideosActivity.kt` (Compose): local library from MediaStore (internal + USB), folders → videos, continue-watching row from the `resume` prefs, thumbnails via `loadThumbnail`. Plays through PlayerActivity with the folder as the playlist (`list`/`names` extras). Header pill "Videos" on the home screen.
- `Yt.kt` + `YouTubeActivity.kt`: NewPipeExtractor v0.26.5 (JitPack, plus core-library desugaring). Tabs Home / Search / History / Channels; long-press a video → its channel page (subscribe there); Home mixes related-to-history with latest uploads of subscribed channels (fallback: trending kiosk, then a search). Playback = `yt:<id>` entries in PlayerActivity: highest ≤1080p video-only stream + Arabic audio track if present (else original) as a libVLC audio slave; `qset` from the phone switches quality. Text typed on the phone (`/type`) becomes a search; YouTube links sent from the phone play directly.
- Not built or run on a device yet.
