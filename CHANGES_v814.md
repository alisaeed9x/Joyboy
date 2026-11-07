# v814 (على v813) — الويندوز المرحلة 2
## 💻 Joyboy Desk (desktop/)
- **مكتبة اللابتوب للموبايل:** `/lib/folders` `/lib/videos` `/lib/thumb` `/file/lib` + فتح `tv://id` من الموبايل ← يشغّل على اللابتوب. (Lib.kt)
- **ابعت للموبايل:** تاب النقل ← زرار + سحب وإفلات ← طابور بيطلع في رد `/ping` (`push`) 40 ثانية. (Push في Lib.kt)
- **هوت سبوت:** Hotspot.kt — بروفايل ويندوز بـnetsh + انضمام تلقائي كل 15 ثانية + لصق بيانات `WIFI:` + تاب في الواجهة.
## 📱 الموبايل (أندرويد)
- **DeskPush.kt (جديد):** بيقرا `push` من رد الـping وبيحمّل الملفات لوحده بمهمة في مركز المهام (X-Token). تكرار بالـid.
- **PhoneHotspot.kt (جديد):** ضغطة طويلة على 📶 في الريموت (ControllerPad) ← إعدادات الهوت سبوت أو هوت سبوت محلي (LocalOnlyHotspot) + نسخ `WIFI:` للكليب بورد.
- `RemoteClient.parseCtx` ← استدعاء `DeskPush.handle`. `AndroidManifest` ← `NEARBY_WIFI_DEVICES` (neverForLocation). الملفين الجداد في `kt_manifest.txt` (check_manifest.sh OK).
- لم يتم compile كامل. كود الويندوز اتفحص بالـkotlinc مقابل stubs بس.
