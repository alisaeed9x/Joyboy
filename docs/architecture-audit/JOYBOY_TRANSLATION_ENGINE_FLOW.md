# ⚙️ JOYBOY TRANSLATION ENGINE FLOW

> HEAD `d3169383` (v13.91). كل ادعاء بمرجع ملف:سطر. Engine.kt = 4065 سطر، فيه ~200 دالة مفهرسة. العلاقات الديناميكية (coroutines/Handler/ThreadLocal) متعلّمة صراحة.

## 1. العزل المعماري: EngineBackend

```
Player ──> EngineBackend (interface) ──> LocalEngine ──> Engine (الحقيقي)
```

- `EngineBackend.kt:14-169` — واجهة بـ 40+ عضو (subs, paused, coverageSegs, translateFrom, keyDots...). التعليق المؤسس (سطور 4-12): «القائمة اتبنت من الاستخدام الفعلي لـ engine. في Player.kt (grep)، مش من التخمين».
- `Player.kt:1980` — `private lateinit var realEngine: Engine` — المشغّل بيكلّم الواجهة؛ الـEngine الحقيقي بيُستخدم جوه `initEngine/startEngine/retireEngine/Live` بس (تعليق Player.kt:1978).
- التصميم مهيأ لـ RemoteEngine (تلفزيون) — مش مفعّل بالكامل بعد (gap).

## 2. نقطة البداية: beginTranslate

`Player.kt:2747 fun beginTranslate(auto)`:

1. فرع بعيد: `remoteBegin(auto)` (تلفزيون).
2. `softGate()` — فحص السوفت سب (v1389: async بعد إصلاح التهنيج).
3. فحص مفتاح: `Cfg.str("mt_tr_mode") == "groq"` بدون `groq_key` ← تحذير (Player.kt:2752).
4. تصفير `hard_mode` لو مش هجين (Player.kt:2754-2756).
5. تسجيل مصدر الطلب في LogStore (Player.kt:2759) — «▶ طلب بدء الترجمة ← methodName».

ثم `Engine.load` (Engine.kt:993) ← `prepareInner` (1481) ← `prePrepTick` (1287).

## 3. خط التفريغ (الصوت ← نص)

```
exportBatchAudio (852) ─> Offline.transcribe ─> SttEngine
                              │                   ├─ WhisperStt (Stt.kt:36, whisper.cpp native)
                              │                   └─ sherpa (مفتاح stt_engine, Stt.kt:6)
                              ├─ Groq (سحابي, يحتاج groq_key)
                              └─ Gemini (سحابي, احتياطي)
```

- واجهة التفريغ: `Stt.kt:22-32` (`prepare/transcribe/release`).
- اختيار المصدر فعليًا لكل باتش مع علامات ThreadLocal: `Engine.kt:1773` — `txSrc = groq|gemini|whisper` حسب اللي اشتغل فعلًا (v1372).
- لو جروك مختار والمفتاح ناقص: اللوج يوضح إن جيميناي اشتغل مكانه (Engine.kt:1703, v1371).
- المسار السريع: `fastRace` (1527) + `fastSplit` (1572) — ترجمة فورية لأول باتشات بالتوازي مع التفريغ الكامل.
- مراجعة جودة التفريغ: `relistenLow` (2279) إعادة استماع للمقاطع الضعيفة، `holdTranscript` (2330) حجب التفريغ المؤقت، `releaseToFull` (2354).

## 4. الجدولة والضغط

| دالة | سطر | دور |
|---|---|---|
| `guard` | 1255 | الحارس الرئيسي للحلقة |
| `dispatch` | 1320 | توزيع الباتشات |
| `hedgeTick` | 1356 | إرسال تحوّطي (hedging) للباتشات البطيئة |
| `pressureGate`/`pressureClear` | 1211/1224 | بوابة ضغط (تخنيق عند التكدس) |
| `nap` | 1230 | تهدئة |
| `netHold` | 1243 | تعليق عند انقطاع الشبكة |
| `rampAllWanted`/`rampSizes`/`rampWindow` | 122/125/1412 | تسريع البداية بباتشات صغيرة (`ramp_all`) |
| `nextKey`/`switchKey` | 1638/1640 | تدوير مفاتيح API |
| `noteQuota`/`quotaCooling`/`capNow` | 2052/2023/2051 | إدارة الحصص (quota) |
| `queueBackup`/`sendOrBackup`/`startBkLane`/`retryOnBackupSeq` | 1855/1869/1878/695 | مسارات احتياطية (backup lanes) |

## 5. خط الترجمة (نص ← نص)

```
translateTick (2355)
  └─> translateGroup (2384)
        └─> translateContextual (2829)
              ├─> buildPrompt (1913) ─> buildPromptCore (1952)
              │        └─ promptExtras (343): toneBlock (257) + glossaryBlock (278)
              │           + fewShotBlock (300) + topicBlock (325) + ctxLinesBlock (334)
              │           + listenBlock (1926) + easternBlock (1945)
              ├─> askJson (2796)  ← مزود فعلي: bgGemini (3060) / groq / partialCall (3305)
              └─> applyChunk (2913) / applyGroupTranslated (2467)
                     └─ protectTags/restoreTags (368-388) + enforceGlossary (286)
```

- **حماية الوسوم**: `protectTags` (368) ← `restoreTags`/`restoreKnownTags` (374/380).
- **تطبيع ASR**: `normAsr` (351).
- **الكاش بالسطر**: `cacheKey` (397)، `lineCacheLoad/Save/Get/Put` (404-444)، `cachePrefill` (445).
- **سياق الباتشات السابقة**: `ctxBatches` (2499) — لو `tr_mode_id == "loc_ctx"` وأوفلاين مهيأ: الحد 2-3 باتشات (Engine.kt:2500).

## 6. المزودون والمفاتيح

| المزود | دوال | مفاتيح |
|---|---|---|
| Gemini | `bgGemini` (3060)، `bgCall` (3050) | `gemini_key` + تدوير |
| Groq | `groqResult` (2095)، `groqTxOn` (2085) | `groq_key`، `mt_tr_mode=groq` |
| Google الجانبي | `viaGoogle` (2727)، `googleItemsRaw` (2711)، `googleItems` (2663) | `side_in_google` |
| محرك خارجي | `viaExt` (2741) ← ExtLlm/ExtTranslate | `ext_strict` |
| مجاني | FreeLlm / PublicApis | — |

## 7. الجودة والتنقيح

`fidelityFix` (487: إصلاح الأرقام/الإخلاص، `missingNums` 465، `retranslateLineSync` 472) ← `refinePass2` (501) ← `batchQuality` (518) ← `crossCheck` (2194: فحص التشابه tnorm/tsim/tcontain 2164-2193) ← `maybeRefine` (3328) ← `runRefine`/`refinePass` (3359/3368) ← `finalRefineTick` (3345) ← `refineWholeNow` (3541). إعادة ترجمة تلقائية للباتش الضعيف: `auto_requal`.

## 8. سد الثغرات (Hole-fill)

`gapTick` (3855) ← `scheduleHoleFill` (3897) ← `holeFill` (3914) ← `holeRequeue`/`holeDeferTick` (2038/2045). مصادر السد: `txFillOffline` (3962: ويشبر أوفلاين)، `txFillSubs` (3981: ملفات ترجمة)، `fillGap` (4015) مع `nearSpeech` (4012). من v1390: المسرد المقفول بيتضاف لبرومبتات سد الثغرات (1955/1963/1969).

## 9. المسرد المقفول (Glossary)

`glossaryPairs` (274) ← `glossaryBlock` (278) ← يدخل كل برومبت عبر `promptExtras` (343). الإنفاذ بعد الترجمة: `enforceGlossary` (286). مسرد منفصل مكتشف من الموديل: `effectiveGloss` (2003) — **مختلف عن مسرد المستخدم** (مصدر التباس موثق — GAP_ANALYSIS).

## 10. التحويل اللهجي

`setConvDialect` (752) ← `adoptDialect` (763) ← `maybeConvert` (768) ← `convViaLlm` (785) ← `convertSubs` (795). المفاتيح: `conv_dialect`, `tone_style`, `tone_strength`.

## 11. الشخصيات والضمائر

`analyzeCharsNow` (974) ← `maybeAnalyze` (3075) ← `maybeLookup`/`lookupCast`/`applyCast` (3098/3113/3129) ← `maybeIdentify`/`identifyFromDialogue` (3145/3161) ← `analyzeChars` (3187) ← `maybePronouns` (3225) ← `pronounPass` (3241) ← `runPronounCorrection` (3254) ← `correctPronounsNow` (3464).

## 12. النسخ والتراجع والحفظ

`saveVersion` (3432) ← `applyVersion` (3434) ← `redo`/`redoFrom`/`redoOnly`/`redoAll` (906-943) ← `loadSnapshot` (944). الحفظ: `persist`/`doPersist` (4050/4056)، `saveNow` (961).

## 13. الإصلاح الشامل

`repairForeignTick` (3779) ← `fixViaGoogle` (3767) ← `googleTranslateAll` (3645) ← `removeDuplicates` (3689) ← `recap` (3716) ← `translateTemplate` (3748).

## 14. تغطية التقدم (للواجهة)

`coverageSegs` (619)، `pendingSegs` (621)، `pendingCount` (622)، `coveredSec` (624)، `inflightSegs` (565)، `batchRows` (575)، `batches` (631)، `problems` (649)، `failedCount` (562) — كلها معرّضة عبر EngineBackend للمشغّل.

## علاقات ديناميكية مهمة (مش نداء مباشر)

- **ThreadLocal** لمصدر التفريغ (Engine.kt:1773) وOffline.slotTl (Stt.kt:41).
- **Coroutines**: dispatch/hedge/backup lanes بتشتغل في scopes متوازية — التحليل static مش بيلتقط التوقيت.
- **Callbacks للمشغّل**: `host.log`/`host.notice` (واجهة host بدل نداء مباشر).
- **Bus**: softSubBust/softSubKey في Player (v1389) — إشعارات حدثية بين Engine و Player.
