# v813 (على v812) — نسخة الويندوز (Joyboy Desk) المرحلة 1
- مجلد `desktop/` جديد: مشروع Kotlin/JVM مستقل (Swing + VLC عبر vlcj)، مفيش أي تغيير في كود الموبايل.
- بيتعامل مع الموبايل بنفس بروتوكول التلفزيون (TvLink) فيظهر في قايمة الريموت: /hello /pair /ping /player /open /cmd /subup /pull /xfer/list /file/any.
- الإرسال والاستقبال: الموبايل→اللابتوب (/pull بـRange واستكمال)، واللابتوب→الموبايل عن طريق مركز النقل (الموبايل يسحب).
- مدير تحميل (لينك مباشر + yt-dlp اختياري).
- البناء: `desktop/build-windows.yml` ينسخ لـ`.github/workflows/` ← يطلع JoyboyDesk-windows.zip (EXE محمول).
- الكود اتفحص بالـkotlinc مقابل stubs (مفيش إنترنت لمكتبات vlcj/org.json هنا) ← لم يتم compile كامل ولا تجربة فعلية.
