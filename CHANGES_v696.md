# v696 — أوفلاين كامل (المراحل 1–3): Whisper turbo + ترجمة Hy-MT2 على الجهاز
- م1: محرك llama.cpp (b12034) + whisper.cpp (v1.9.5) على ggml واحدة · LocalMt / ModelStore / Offline (موديل turbo، اختيار ملف SAF من غير نسخ).
- م2: LocalMt بقى مزوّد ترجمة في Engine.googleItemsRaw (بعد دمج الجمل المقطّعة OffLang). التفريغ والترجمة ورا بعض: الترجمة بتبدأ بعد ما كل الباتشات تتفرّغ، Whisper بيتحرّر بقفل الكتابة (Offline.releaseWhisper) قبل تحميل Hy-MT2، وLocalMt بيتحرّر قبل أي تفريغ جديد. جملة تفشل تفضل بأصلها + LogStore.
- م3: وضع «📴 أوفلاين كامل» (loc_mt): Strict.MT / mt() / hideTrOpts() · noGemini()/noGemKeys() = true · ويزارد (مفيش أسئلة لهجة) · ملخص الخطة · curModeKey. الإعدادات القديمة ما اتلمستش.
- م4: إخفاء اللهجات في «📴 أوفلاين كامل» بـ Strict.hideTrOpts(): قسم اللهجة والأسلوب في الإعدادات (Main) · عناصر قايمة التلفزيون في Player · أزرار ✨/🌐 في PlayerPad (ريموت الموبايل) · TrWizard.dialectWanted() و SubFileTr.effLang() بيرجّعوا فصحى · إشعار اللهجة. باقي الأزرار (🌐/🗣/إعدادات الوضع) كانت أصلًا بتتحكم فيها caps() وده الوضع بقى «off» فيها. أي لهجة محفوظة (lang/conv_dialect) بتتعامل كفصحى هنا بس من غير مسح الإعدادات الأونلاين.
- م5: شاشة «📦 الموديلات الأوفلاين» (الإعدادات ← 🎙 التفريغ والصوت، ModelsUi.kt): لكل موديل (Whisper turbo · Hy-MT2 Q4/Q6) الحالة والحجم · «اختار الملف» (ACTION_OPEN_DOCUMENT → ModelStore.attach، مع try/catch للتلفزيون) · «نزّل من التطبيق» (Offline.fetchModel بالاستكمال، فحص المساحة، منع نوم الشاشة، إلغاء) · نسخ الرابط · فصل الملف المختار · اختيار Q4/Q6. ModelStore.restore بيتنادى من Cfg.init. Offline.status() و LocalMt.status() معروضين فوق.
- لسه: م6 (ضبط بعد أول بناء/تجربة) · م7 (التلفزيون).
- ما اتكومبايلش ولا اتجرّب على جهاز.
- اتأكد من صفحة tencent/Hy-MT2-1.8B-GGUF: Hy-MT2-1.8B-Q6_K.gguf (1.47GB) و Q4_K_M (1.13GB) أسماؤهم صح.
