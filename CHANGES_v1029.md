# v1029 — إصلاح وقوع R8 (dnsjava)
**ما اتبنّاش عندي — Actions هو الحكم.** الأساس: v1028.

## السبب (من لوج البناء)
- `:app:minifyDebugWithR8 FAILED` — «Missing classes detected»: dnsjava (`org.xbill.DNS`، جاي من MeeTube/sharedutils) بيشاور على `com.sun.jna.*` و`javax.naming.*` و`lombok.Generated` — كلاسات مش موجودة على أندرويد (فروع Windows/JNDI عمرها ما بتتنفّذ هنا).
- ده أول ظهور له لأن v1028 فعّلت R8 على debug (كان مقفول قبلها).

## الإصلاح
- `app/proguard-rules.pro`: `-dontwarn` لـ `com.sun.jna.**` و`javax.naming.**` و`lombok.**` و`org.xbill.DNS.config.**` و`org.slf4j.impl.**` (نفس اللي كان هيطلع في `missing_rules.txt`).
- مفيش تغيير في الكود ولا السلوك.

⚠ R8 لسه جديد على كود MeeTube — لو طلعت أخطاء تانية بعد الـMissing class ابعت اللوج. الطوارئ: `joyMinify=false` في gradle.properties.
