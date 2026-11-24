# Joyboy v1223 — كروت أنعم + تصغير الـAPK

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- **الشكل الجديد**: كل الكروت والصناديق (الفيديوهات، الإعدادات، الشيتات) بقت بزوايا أنعم وأكبر شوية. الشكل القديم زي ما هو.
- أيقونة البحث الدايرة جنب خانة البحث كانت بيضا على أبيض في الفاتح؛ بقت ظاهرة في الشكل الجديد.

## 📦 حجم أصغر
- اتحذف 96 ملف برومبت كانوا مكررين (المحتوى نفسه بالحرف جوه الحزمة `_pack.txt` واتفحص واحد واحد). البرنامج بيقرا من الحزمة، فمفيش أي تغيير في السلوك. توفير حوالي 1.7 ميجا من الـAPK.

## ⚠️ خد بالك
- لو ترجمة باظت بعد التحديث ده قولّي فورًا (مش متوقع).

<details><summary>التفاصيل التقنية</summary>
Ui.kt: box() بيزوّد 6 لأي نصف قطر بين 10 و16 لما Design.isNew(). LibraryUi.kt: لون أيقونة 🔎. _delete.txt: 96 ملف app/src/main/assets/prompts/&lt;id&gt;.txt كلهم موجودين في _pack.txt.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1223.md
✏️ عدّل:   STATUS.txt
🗑️ حذف:   app/src/main/assets/prompts/01d1752aad.txt
🗑️ حذف:   app/src/main/assets/prompts/020dc155ab.txt
🗑️ حذف:   app/src/main/assets/prompts/0505b1870d.txt
🗑️ حذف:   app/src/main/assets/prompts/05884e6d6f.txt
🗑️ حذف:   app/src/main/assets/prompts/08e10386ec.txt
🗑️ حذف:   app/src/main/assets/prompts/0966956919.txt
🗑️ حذف:   app/src/main/assets/prompts/0c62fc0180.txt
🗑️ حذف:   app/src/main/assets/prompts/11b053540c.txt
🗑️ حذف:   app/src/main/assets/prompts/14df681d78.txt
🗑️ حذف:   app/src/main/assets/prompts/165a328467.txt
🗑️ حذف:   app/src/main/assets/prompts/1b2e7ff865.txt
🗑️ حذف:   app/src/main/assets/prompts/1b4cc0a1ef.txt
🗑️ حذف:   app/src/main/assets/prompts/1ff2fe28e1.txt
🗑️ حذف:   app/src/main/assets/prompts/211b476eb2.txt
🗑️ حذف:   app/src/main/assets/prompts/233589b161.txt
🗑️ حذف:   app/src/main/assets/prompts/26e2fbf62c.txt
🗑️ حذف:   app/src/main/assets/prompts/2c49d4b8ff.txt
🗑️ حذف:   app/src/main/assets/prompts/2f3b700319.txt
🗑️ حذف:   app/src/main/assets/prompts/352b39e114.txt
🗑️ حذف:   app/src/main/assets/prompts/35b919af56.txt
🗑️ حذف:   app/src/main/assets/prompts/35eb477f69.txt
🗑️ حذف:   app/src/main/assets/prompts/368c8b1ea9.txt
🗑️ حذف:   app/src/main/assets/prompts/3949543e1a.txt
🗑️ حذف:   app/src/main/assets/prompts/3ae84fc8f5.txt
🗑️ حذف:   app/src/main/assets/prompts/3b60269767.txt
🗑️ حذف:   app/src/main/assets/prompts/402c045a37.txt
🗑️ حذف:   app/src/main/assets/prompts/452ffc0ed1.txt
🗑️ حذف:   app/src/main/assets/prompts/4aa5096fd6.txt
🗑️ حذف:   app/src/main/assets/prompts/4d75e825cb.txt
🗑️ حذف:   app/src/main/assets/prompts/571f22bde2.txt
🗑️ حذف:   app/src/main/assets/prompts/57280e0d28.txt
🗑️ حذف:   app/src/main/assets/prompts/59a348415f.txt
🗑️ حذف:   app/src/main/assets/prompts/5ad687e383.txt
🗑️ حذف:   app/src/main/assets/prompts/5cd4705a85.txt
🗑️ حذف:   app/src/main/assets/prompts/5e7d4c2b68.txt
🗑️ حذف:   app/src/main/assets/prompts/5f096526a1.txt
🗑️ حذف:   app/src/main/assets/prompts/644f8b2bf0.txt
🗑️ حذف:   app/src/main/assets/prompts/6570f665fd.txt
🗑️ حذف:   app/src/main/assets/prompts/6b07b3061f.txt
🗑️ حذف:   app/src/main/assets/prompts/71fbf64acb.txt
🗑️ حذف:   app/src/main/assets/prompts/73e8dc4297.txt
🗑️ حذف:   app/src/main/assets/prompts/763fbef7d1.txt
🗑️ حذف:   app/src/main/assets/prompts/767848913d.txt
🗑️ حذف:   app/src/main/assets/prompts/7892f75e52.txt
🗑️ حذف:   app/src/main/assets/prompts/7b4e231ef2.txt
🗑️ حذف:   app/src/main/assets/prompts/7bec36b718.txt
🗑️ حذف:   app/src/main/assets/prompts/7ea84c889b.txt
🗑️ حذف:   app/src/main/assets/prompts/7f2d096201.txt
🗑️ حذف:   app/src/main/assets/prompts/8239ce704d.txt
🗑️ حذف:   app/src/main/assets/prompts/83df2e5161.txt
🗑️ حذف:   app/src/main/assets/prompts/86a489df7e.txt
🗑️ حذف:   app/src/main/assets/prompts/888342e9fa.txt
🗑️ حذف:   app/src/main/assets/prompts/8aeb5acc3d.txt
🗑️ حذف:   app/src/main/assets/prompts/8f7fa44cb2.txt
🗑️ حذف:   app/src/main/assets/prompts/9d96adaab3.txt
🗑️ حذف:   app/src/main/assets/prompts/9f083f43e0.txt
🗑️ حذف:   app/src/main/assets/prompts/9f5a9cf0a8.txt
🗑️ حذف:   app/src/main/assets/prompts/a5582f4adc.txt
🗑️ حذف:   app/src/main/assets/prompts/a7ef76ba75.txt
🗑️ حذف:   app/src/main/assets/prompts/a9d8191794.txt
🗑️ حذف:   app/src/main/assets/prompts/b0af204aa4.txt
🗑️ حذف:   app/src/main/assets/prompts/b1666a127f.txt
🗑️ حذف:   app/src/main/assets/prompts/b232afcb1c.txt
🗑️ حذف:   app/src/main/assets/prompts/b3cb1cd5f0.txt
🗑️ حذف:   app/src/main/assets/prompts/b61d7cd3d1.txt
🗑️ حذف:   app/src/main/assets/prompts/b9e64c5c9b.txt
🗑️ حذف:   app/src/main/assets/prompts/bd6b4e117a.txt
🗑️ حذف:   app/src/main/assets/prompts/be5c19d08f.txt
🗑️ حذف:   app/src/main/assets/prompts/c24f881db3.txt
🗑️ حذف:   app/src/main/assets/prompts/c408d2423e.txt
🗑️ حذف:   app/src/main/assets/prompts/c523335d2d.txt
🗑️ حذف:   app/src/main/assets/prompts/c56c7ed6f2.txt
🗑️ حذف:   app/src/main/assets/prompts/c804d25589.txt
🗑️ حذف:   app/src/main/assets/prompts/cb90092cdd.txt
🗑️ حذف:   app/src/main/assets/prompts/cd04cc80c9.txt
🗑️ حذف:   app/src/main/assets/prompts/d0c76998b2.txt
🗑️ حذف:   app/src/main/assets/prompts/d16534768b.txt
🗑️ حذف:   app/src/main/assets/prompts/d26c0154bb.txt
🗑️ حذف:   app/src/main/assets/prompts/d77629dd8d.txt
🗑️ حذف:   app/src/main/assets/prompts/da132fd924.txt
🗑️ حذف:   app/src/main/assets/prompts/db0410540c.txt
🗑️ حذف:   app/src/main/assets/prompts/dcddd378a9.txt
🗑️ حذف:   app/src/main/assets/prompts/ddb159f455.txt
🗑️ حذف:   app/src/main/assets/prompts/de5af4e912.txt
🗑️ حذف:   app/src/main/assets/prompts/e68c4a01a7.txt
🗑️ حذف:   app/src/main/assets/prompts/e761f2f2d1.txt
🗑️ حذف:   app/src/main/assets/prompts/e76642eecf.txt
🗑️ حذف:   app/src/main/assets/prompts/eeb78d38c9.txt
🗑️ حذف:   app/src/main/assets/prompts/f02d9387f8.txt
🗑️ حذف:   app/src/main/assets/prompts/f26e629778.txt
🗑️ حذف:   app/src/main/assets/prompts/f3392930b5.txt
🗑️ حذف:   app/src/main/assets/prompts/f411c847dc.txt
🗑️ حذف:   app/src/main/assets/prompts/f7bdd7f3da.txt
🗑️ حذف:   app/src/main/assets/prompts/fa5d1ea5d8.txt
🗑️ حذف:   app/src/main/assets/prompts/fc477d9b06.txt
🗑️ حذف:   app/src/main/assets/prompts/ff233bff83.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/LibraryUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Ui.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1222-run34` (للرجوع: Actions ← Rollback)
