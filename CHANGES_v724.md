# v724
- إصلاح البيلد: ملفات v722 الجديدة `NetGate.kt` و`NetLedger.kt` و`NetStatsUi.kt` ماكانتش في `app/kt_manifest.txt`، فخطوة `pruneStaleKt` كانت بتمسحها وقت البناء وكوتلن يطلّع `Unresolved reference 'NetLedger' / 'NetGate' / 'NetStatsUi'` (BgTranslate · Browser · Downloader · Main · NetUse · Player · QueueUi). اتسجّلوا في المانيفست (240 ملف).
- الإصدار 7.24.
- اتأكدت: `bash dev-tests/check_manifest.sh` ناجح.
