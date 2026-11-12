#!/data/data/com.termux/files/usr/bin/bash
# تشغيل OmniRoute في Termux من غير مفتاح (محلي بس). مجرّب على الورق مش على جهازك — لو حاجة وقفت ابعتلي رسالة الخطأ.
#   bash termux_omniroute.sh            ← أحدث نسخة منشورة على npm (3.8.52 لو اتنشرت وإلا الأحدث المتاح)
#   MODE=source bash termux_omniroute.sh ← بناء 3.8.52 من فرع GitHub (release/v3.8.52) — تقيل وبياخد وقت/رام
set -e
pkg update -y && pkg install -y nodejs python build-essential git
node --version
export REQUIRE_API_KEY=false      # الافتراضي أصلًا؛ بيسمح بطلبات بدون مفتاح على الجهاز بس
export PORT=20128
termux-wake-lock 2>/dev/null || true
if [ "$MODE" = "source" ]; then
  [ -d "$HOME/OmniRoute" ] || git clone --depth 1 -b release/v3.8.52 https://github.com/diegosouzapw/OmniRoute "$HOME/OmniRoute"
  cd "$HOME/OmniRoute" && npm ci && npm run build
  nohup npm start > "$HOME/omniroute.log" 2>&1 &
else
  npm install -g omniroute@3.8.52 2>/dev/null || npm install -g omniroute@latest
  nohup omniroute > "$HOME/omniroute.log" 2>&1 &
fi
echo "تمام. الداشبورد: http://localhost:20128 — ضيف مزوّد واحد على الأقل من Providers، وبعدها افتح 🤖 في Subtitler."
echo "اللوج: $HOME/omniroute.log"
