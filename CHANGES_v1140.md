# Joyboy v1140 — إصلاح كراش فتح فيديو يوتيوب (PlayerActivity.onCreate)

> **لم يتم compile ولا اتجرّب على جهاز** — Actions هو الحكم. الأساس: v1139.

## الكراش
`NullPointerException: PlaybackBackend.isPlaying() on a null object reference` — Player.kt:5601 ← `applyChromeFn` ← `applyFull` ← `onCreate` (سطر 5930).

## السبب
- `player` متعرّف `lateinit` وبيتعيّن في `buildPlayer()` (سطر ~5936) **بعد** `applyFull(wantFull())` في `onCreate`.
- في صفحة يوتيوب (`ytPage`) في الوضع الرأسي `wantFull()` بترجّع false ← `applyFull(false)` ← `applyChromeFn()` بـ`on = false` ← سطر `centerPlay.visibility` بيوصل لـ`player.isPlaying` قبل ما المشغّل يتبني = كراش.
- الفيديو المحلي ما كانش بيكراش لأن `on = true` هناك فالشرط بيقف قبل ما يلمس `player`.

## الإصلاح (Player.kt سطر 5601)
`player.isPlaying` ← `::player.isInitialized && player.isPlaying` (قبل ما المشغّل يتبني الزرار الأوسط يتعامل معاه إنه مش شغّال). مفيش أي استخدام تاني لـ`player` في `applyChromeFn` / `applyFull` / `applyPageChrome` / `placeCard` (اتفحصوا).

## ⚠ للتجربة
1. افتح أي فيديو يوتيوب (رأسي ثم أفقي): مفيش كراش، الصفحة بتفتح.
2. فيديو محلي: نفس السلوك زي v1139.
