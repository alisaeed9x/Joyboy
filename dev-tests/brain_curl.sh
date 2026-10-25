#!/data/data/com.termux/files/usr/bin/sh
# اختبار م1 من Termux (أو أي جهاز على نفس الشبكة) من غير موبايل ريموت:
#   sh dev-tests/brain_curl.sh <ip-التلفزيون>[:18770] [كود-الإقران لو فيه ريموت متقرن قبل كده]
# المطلوب: التلفزيون شغّال وفيه فيديو مفتوح في المشغّل (عشان /sync و/frame و/rpc يردّوا بحاجة).
TV="${1:?اكتب عنوان التلفزيون ip أو ip:18770}"
case "$TV" in *:*) ;; *) TV="$TV:18770" ;; esac
CODE="${2:-}"
echo "== pair"
TOKEN=$(curl -s -X POST "http://$TV/pair" -d "{\"id\":\"curltest\",\"name\":\"curl\",\"code\":\"$CODE\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/')
[ -z "$TOKEN" ] && { echo "فشل الإقران (محتاج كود؟)"; exit 1; }
H="x-token: $TOKEN"
echo "== ping";  curl -s -H "$H" "http://$TV/ping"; echo
echo "== sync (أول مرة since=0)"; curl -s -H "$H" "http://$TV/sync?since=0&wait=3" | head -c 700; echo
echo "== rpc pause"; curl -s -H "$H" -X POST "http://$TV/rpc" -d '{"cid":"t1","op":"pause"}'; echo
echo "== rpc pause تاني بنفس cid (لازم نفس الرد من غير تنفيذ)"; curl -s -H "$H" -X POST "http://$TV/rpc" -d '{"cid":"t1","op":"pause"}'; echo
echo "== rpc seekto 60s"; curl -s -H "$H" -X POST "http://$TV/rpc" -d '{"cid":"t2","op":"seekto","args":{"ms":60000}}'; echo
echo "== rpc play"; curl -s -H "$H" -X POST "http://$TV/rpc" -d '{"cid":"t3","op":"play"}'; echo
echo "== engine.get"; curl -s -H "$H" -X POST "http://$TV/rpc" -d '{"cid":"t4","op":"engine.get"}'; echo
echo "== frame"; curl -s -H "$H" "http://$TV/frame?w=480&q=60" -o /sdcard/brain_frame.jpg -w "http=%{http_code} bytes=%{size_download}\n"
echo "== lib/folders"; curl -s -H "$H" "http://$TV/lib/folders" | head -c 500; echo
echo "== cfg"; curl -s -H "$H" "http://$TV/cfg?keys=conv_dialect,sub_scale,tone_style"; echo
echo "== bye"; curl -s -H "$H" "http://$TV/bye"; echo
