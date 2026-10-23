#!/bin/sh
# Smoke على جهاز/محاكي متوصّل بـ adb (P12). بيثبّت الـ APK ويمنح إذن الفيديو ويشغّل ويبعت أزرار ريموت ويفحص إن مفيش كراش.
# ده مش بديل التجربة اليدوية على TCL (مفيش فيه مشغّل/كودكات/يوتيوب). الاستخدام: sh dev-tests/adb_smoke.sh [مسار-APK]
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PKG=com.vanilla.tvlauncher
fails=0
ok() { echo "PASS $1"; }
ko() { echo "FAIL $1"; fails=$((fails+1)); }
command -v adb >/dev/null 2>&1 || { echo "adb مش موجود في PATH"; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "مفيش جهاز متوصّل (جرّب: adb devices)"; exit 2; }
[ -f "$APK" ] || { echo "ملف APK مش موجود: $APK"; exit 2; }

SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "الجهاز: $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r') — Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $SDK)"

adb install -r "$APK" 2>&1 | grep -q Success && ok "install" || ko "install"
if [ "${SDK:-0}" -ge 33 ] 2>/dev/null; then P=android.permission.READ_MEDIA_VIDEO; else P=android.permission.READ_EXTERNAL_STORAGE; fi
adb shell pm grant "$PKG" "$P" >/dev/null 2>&1 && ok "grant $P" || ko "grant $P"

D=$(adb shell dumpsys package "$PKG" | tr -d '\r')
echo "$D" | grep -q "android.intent.category.HOME" && ok "HOME filter مسجّل" || ko "HOME filter مسجّل"
echo "$D" | grep -q "android.intent.category.LEANBACK_LAUNCHER" && ok "LEANBACK_LAUNCHER مسجّل" || ko "LEANBACK_LAUNCHER مسجّل"

adb logcat -c >/dev/null 2>&1
adb shell am start -n "$PKG/.HomeActivity" >/dev/null 2>&1 && ok "am start" || ko "am start"
sleep 5
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] && ok "التطبيق شغّال بعد 5ث" || ko "التطبيق شغّال بعد 5ث"

# ريموت: يمين/تحت/OK/Back عدّة مرات (بيجرّب التنقل والرجوع من غير ما يفتح فيديو بالضرورة)
for k in KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER KEYCODE_BACK KEYCODE_BACK; do
  adb shell input keyevent "$k"; sleep 1
done
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] && ok "التطبيق لسه شغّال بعد الأزرار (Back ما قفلش العملية)" || ko "التطبيق لسه شغّال بعد الأزرار"

if adb logcat -d 2>/dev/null | grep -A6 "FATAL EXCEPTION" | grep -q "$PKG"; then ko "مفيش كراش في اللوج"; adb logcat -d | grep -A12 "FATAL EXCEPTION" | head -40; else ok "مفيش كراش في اللوج"; fi

echo "---"; [ "$fails" -eq 0 ] && echo "ALL PASS (smoke)" || echo "FAILURES: $fails"
exit "$fails"
