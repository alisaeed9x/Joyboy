# Joyboy v1083 — إصلاح فشل بيلد v1082 (خطوة ما بعد الكومبايل)

## الفشل
`:app:produceDebugComposeMapping` — `Could not find org.jetbrains.kotlin:compose-group-mapping:2.2.10`.
الكومبايل (`compileDebugKotlin`) عدّى خلاص؛ الفشل في خطوة R8/Compose mapping.

## السبب
كوتلن المدمج في AGP 9.2.1 نسخته 2.2.10، بينما بلجن الكومبوز 2.3.21. المهمة بتدوّر على artifact بنسخة كوتلن المدمج ومش لاقياه.

## الإصلاح (ملف واحد: build.gradle في الجذر)
إضافة `classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21'` في buildscript.

## مش متأكد منه
- ما اتبنّاش هنا (مفيش نت) — الـActions هو الحكم.
- مش متأكد 100% إن artifact بنسخة 2.3.21 موجود على Maven؛ لو البيلد فشل بنفس الرسالة بنسخة 2.3.21، الحل السريع: `joyMinify=false` في gradle.properties.
