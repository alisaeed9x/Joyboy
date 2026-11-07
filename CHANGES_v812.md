# v812 (على v811) — إصلاح فشل البيلد
- **السبب:** `AudioBridge.kt` و`Haptic.kt` ما كانوش في `app/kt_manifest.txt` ← `pruneStaleKt` مسحهم وقت البناء ← Unresolved reference 'AudioBridge' / 'Haptic' (ControllerPad, IrTv, MusicUi, Player, PlayerPad, TvLink).
- **الإصلاح:** الملفين اتضافوا للمانيفست. `bash dev-tests/check_manifest.sh` ← OK.
- مفيش أي تغيير تاني في الكود. لم يتم compile.
