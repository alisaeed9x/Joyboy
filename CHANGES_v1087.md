# Joyboy v1087 — إصلاح بناء v1086
- `YoutubeSlot.kt`: `YtAudio.track` كان بيبعت id كـ String لـ `Track` (المطلوب Long) — رجّعت نفس حساب المعرّف القديم.
