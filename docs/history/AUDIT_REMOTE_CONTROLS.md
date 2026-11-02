# AUDIT_REMOTE_CONTROLS — الريموت والتركيز والـBack

الحالة: **قراءة كود فقط**. لا اختبار على تلفزيون ولا ريموت حقيقي. «موثّق» = مثبت من الكود؛ «غير مختبر» = لم يُشغَّل.

## قاعدة المسؤولية (المطلوبة): التطبيق في المقدمة يتعامل مع الريموت
الكود يطابقها في الغالب: كل Activity تعالج مفاتيحها في `dispatchKeyEvent` الخاص بها، ولا توجد طبقة عامة تبلع مفاتيح تطبيق آخر (انظر TvAccessService).

## طبقات استقبال المفاتيح
| الطبقة | الملف:سطر | ما تفعله (موثّق) |
|---|---|---|
| TvActivity.dispatchKeyEvent | TvHome.kt:204 | wake(); TvKill.watch; muteHold (كتم 5ث = بحث صوتي، الكتم يعمل); أول مفتاح أثناء شاشة التوقف يُبلع (ما عدا الصوت و0); VOICE_ASSIST/SEARCH → بحث صوتي; `shortcutKey` (اختصارات المستخدم، تتجاهل KEYS_PASS/KEYS_RESERVED، ولا تعمل أثناء التركيز على EditText); الباقي للمنصة (focus افتراضي) |
| MainActivity.dispatchKeyEvent | Main.kt:1773 | TvKill.watch؛ `TvFocus.handleKey` = Menu فقط (long-click على العنصر المركّز عند وضع الريموت) |
| PlayerActivity.dispatchKeyEvent | Player.kt:371 | في وضع الريموت: الصوت → `tvVolume` (يُبلع)؛ play/pause/next/prev + أرقام 0-9 → تُعالج وتُبلع؛ شريط ظاهر: UP/DOWN بين العدادات والشريط؛ مخفي: LEFT/RIGHT تقديم/تأخير 10/30/60ث حسب التكرار، OK يُظهر الشريط؛ «الحلقة الجاية»: OK تشغيل، Back إلغاء |
| BrowserActivity.dispatchKeyEvent → tvKey | Browser.kt:1899 / :1853 | Menu يبدّل شريط/صفحة؛ في الشريط: Back/DOWN → الصفحة؛ في الصفحة: play/pause/rewind/FF → JS للفيديو، CH±/PAGE± → تمرير؛ وضع المؤشر: الأسهم تحرّك المؤشر وOK ينقر؛ وضع التركيز: الأسهم وOK للصفحة نفسها؛ **Back داخل الصفحة غير مبلوع** فيصل إلى `onBackPressed` (history ثم إغلاق) |
| FileManagerActivity | FileManager.kt:611 | TvKill.watch + منطق خاص (لم يُفصَّل) |
| TvAccessService | TvInput.kt:234 | **لا يبلع مفاتيح فعلية**: لا يوجد override لـ`onKeyEvent`، و`tv_access_config.xml` بدون `flagRequestFilterKeyEvents`، و`onAccessibilityEvent` فارغ. يعمل فقط عند استدعاء `key()/ptr()` من ريموت الموبايل (Back عام، DPAD عبر GLOBAL_ACTION_DPAD_* على Android 13+، أو focusSearch لأقدم). مستدعيه لم يُتتبَّع (مرشّح: RemoteService/TvLink) |
| TvKill | TvKill.kt | عند الضغط المستمر على 0 لمدة 5ث (غير مفاجئ للمفتاح؛ لا يبلعه) → `ACTION_DELETE` لحزمة التطبيق (النظام يطلب تأكيداً). مستدعى في Main/Browser/Player/FileManager/TvActivity. الشرط الوحيد: التركيز ليس `EditText` |

## الأزرار
| الزر | السلوك الموثّق | ملاحظات |
|---|---|---|
| DPAD | منصة افتراضية في اللانشر/Main؛ مخصّص في Player (تقديم) وBrowser (مؤشر) | لا خريطة اتجاهات مخصصة زائدة في اللانشر |
| OK | منصة؛ Player يُظهر الشريط؛ Browser ينقر/يبدّل | |
| Back | اللانشر: يغلق فولدر أو يرجع للرئيسية ولا يخرج؛ Main: انظر الجرد؛ Browser: history ثم إغلاق | مسارات Back بين Main↔TvActivity تعتمد على `fromTv` (فرضية H-01) |
| Home | **غير معترض** في الكود (سلوك النظام). لكن TvActivity مسجَّلة HOME | يمكن اختيار التطبيق كلانشر افتراضي |
| Menu | Main: long-click؛ Browser: شريط/صفحة؛ Player: إظهار الشريط | |
| Play/Pause/FF/RW | Player وBrowser (JS على الفيديو) | |
| Volume | Player يبلعها ويستخدم `tvVolume`; اللانشر يمرّرها | |
| 0-9 | Player: قفز لرقم (`tvDigit`)؛ **0 المستمر = TvKill** | ازدواج معنى 0 (H-03) |

## مواضيع لم تُفحص
focus restoration بعد إغلاق الحوار، تكرار الأحداث في الحوارات (GDialog/GAlert)، تمرير الصفحة لمركّز خارج الشاشة، TvPointer/TvScale/TvPlayerBar تفصيلياً. **كلها «اختبار لم يُنفَّذ».**
