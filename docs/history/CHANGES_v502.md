# v5.02 — إصلاح بناء v500/v501
- TvHome.kt: import android.view.ViewGroup ناقص (كودي في v500: firstFocusable وonResume). ده كان الخطأ الوحيد في compileDebugKotlin (run لوج 20:41).
- باقي اللوج: تحذيرات C++ فقط (unknown target CPU armv8.2 ملاحظة لـx86، وwstring_convert deprecated) — مش سبب الفشل.
