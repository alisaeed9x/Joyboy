# Joyboy v1084 — إصلاح فشل البناء

- `Main.kt`: `onRequestPermissionsResult` بقى `Array<String>` و`onNewIntent` بقى `Intent` (غير nullable) عشان يطابقوا توقيع الـ SDK الجديد. مفيش أي تغيير في السلوك.
