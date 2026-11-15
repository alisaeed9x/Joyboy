# v1039 — إصلاح وقوع بيلد v1038
**ما اتعملش compile — Actions هو الحكم.** الأساس: v1038.

## السبب
`YtMiniCard.kt:58` — جوه الـ`object : FrameLayout` المجهول، `parent` كان بيتحلّ لـ`View.getParent()` (من نوع ViewParent) مش لـ`parent` بتاع الكلاس، فـ`.width` و`.height` طلعوا Unresolved reference.

## الإصلاح
- السطر 58: `parent.width/height` ← `this@YtMiniCard.parent.width/height`. مفيش تغيير سلوك.
