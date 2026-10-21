# v466 — إصلاح R8 في بناء الـ release

- كومبايل Kotlin عدّى خلاص (إصلاحات v465 اشتغلت).
- R8 كان بيقع على «Missing class java.beans.* / javax.script.ScriptEngineFactory» (جايين من مكتبة Rhino) — أضفنا قواعد `-dontwarn` في proguard-rules.pro.
