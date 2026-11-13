package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JOYBOY(v952): player motion + button map recorder (debug tool, OFF by default).
 *
 * <p>When enabled it writes a text log (app external files dir / PlayerTrace / trace_*.log) with:
 * <ul>
 *   <li><b>Frame-by-frame motion</b> of every video surface (TextureView / SurfaceView / the player's zoom box / mini card):
 *   on-screen x, y, width, height, alpha and the per-frame delta, at every vsync (Choreographer). A "segment" starts when
 *   something moves and ends 250 ms after it stops, with a summary + verdict: smooth, or janky / jumping / reversing / teleporting.</li>
 *   <li><b>Lifecycle</b> of every activity (created/resumed/paused/…), so a video that "moves from one place to another" between
 *   screens is visible as an activity switch.</li>
 *   <li><b>Marks</b> from the player code: open (media / browser / YouTube), first frame, minimize, maximize, PiP, back, orientation.</li>
 *   <li><b>Buttons map</b>: every visible clickable view with id, text, exact on-screen bounds (px + dp, distance from left / right edge),
 *   a full dump when a screen opens and a NEW/GONE diff after every tap; every tap with the view it hit.</li>
 *   <li><b>Popups</b> (the ☰ 🧰 🗣 ⏱ … menus): anchor bounds, popup bounds, gap and side, and the items inside.</li>
 * </ul>
 */
public final class PlayerTrace {
    private PlayerTrace() { }

    /** forwards important lines (marks, segment summaries) to the app's own log (LogStore). */
    public interface Sink { void line(String s); }

    public static volatile Sink sink;
    public static volatile boolean enabled;

    private static final String PREF = "joy_player_trace";
    private static final long END_IDLE_MS = 250L;
    private static final int MAX_DUMP_LINES = 220;

    private static Application app;
    private static boolean installed;
    private static WeakReference<Activity> cur = new WeakReference<>(null);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final StringBuilder buf = new StringBuilder();
    private static File file;
    private static long t0;

    // ---- sampler state ----
    private static boolean running;
    private static long prevNanos;
    private static long frameNo;
    private static float frameMs = 16.7f;
    private static Map<String, float[]> prevSnap = new LinkedHashMap<>();
    private static String prevActName = "";
    private static boolean inSeg;
    private static long segStartMs, lastChangeMs;
    private static int segFrames, segJank, segStall, segJump, segRev, segTele, segAlpha;
    private static float segWorstDt;
    private static final List<Float> segDts = new ArrayList<>();
    private static String segCause = "";
    private static String pendingCause = "";
    private static long pendingCauseAt;
    private static Map<String, float[]> segStart = new LinkedHashMap<>();
    private static final Map<String, float[]> lastStep = new HashMap<>();   // key -> {dx,dy,m1,m2,m3}
    private static final Map<Integer, String> idNames = new HashMap<>();

    // ---- buttons diff state ----
    private static Set<String> lastKeys = new HashSet<>();

    // =====================================================================================

    public static synchronized void install(Application a) {
        if (installed || a == null) return;
        installed = true;
        app = a;
        SharedPreferences sp = a.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity x, Bundle b) { life(x, "created"); }
            @Override public void onActivityStarted(Activity x) { life(x, "started"); }
            @Override public void onActivityResumed(Activity x) {
                cur = new WeakReference<>(x);
                life(x, "resumed  orient=" + (x.getResources().getConfiguration().orientation == 2 ? "LAND" : "PORT"));
                if (enabled) main.postDelayed(() -> { if (cur.get() == x) dumpButtons("screen opened", x); }, 1000L);
            }
            @Override public void onActivityPaused(Activity x) { life(x, "paused"); }
            @Override public void onActivityStopped(Activity x) { life(x, "stopped"); }
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) { }
            @Override public void onActivityDestroyed(Activity x) { life(x, "destroyed" + (x.isFinishing() ? " (finishing)" : "")); }
        });
        if (sp.getBoolean("on", false)) setEnabled(a, true);
    }

    public static boolean isEnabled() { return enabled; }

    /** @return the log file path when enabled, else null */
    public static synchronized String setEnabled(Context c, boolean on) {
        try { c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply(); } catch (Throwable ignored) { }
        if (on == enabled) return file != null ? file.getAbsolutePath() : null;
        if (!on) {
            endSegment();
            log("TRACE OFF", true);
            flush();
            enabled = false;
            return null;
        }
        try {
            File dir = c.getApplicationContext().getExternalFilesDir("PlayerTrace");
            if (dir == null) dir = new File(c.getApplicationContext().getFilesDir(), "PlayerTrace");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null && old.length > 5) {
                Arrays.sort(old);
                for (int i = 0; i < old.length - 4; i++) { try { old[i].delete(); } catch (Throwable ignored) { } }
            }
            file = new File(dir, "trace_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log");
        } catch (Throwable t) {
            file = null;
        }
        t0 = System.currentTimeMillis();
        enabled = true;
        frameNo = 0; prevNanos = 0; inSeg = false; prevSnap.clear(); lastStep.clear(); lastKeys.clear();
        Activity a = cur.get();
        StringBuilder h = new StringBuilder("TRACE ON  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  SDK ").append(Build.VERSION.SDK_INT);
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            float hz = wm.getDefaultDisplay().getRefreshRate();
            if (hz > 20f) frameMs = 1000f / hz;
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            h.append("  screen ").append(dm.widthPixels).append('x').append(dm.heightPixels).append("px  density ").append(dm.density)
                    .append("  refresh ").append(String.format(Locale.US, "%.1fHz (frame=%.1fms)", hz, frameMs));
        } catch (Throwable ignored) { }
        log(h.toString(), true);
        log("legend: Δ = change since previous frame · JANK = frame later than 1.5x vsync · STALL = >50ms · JUMP = step 3x bigger than recent · REVERSE = direction flipped · TELEPORT = moved >25% of screen in one frame", false);
        if (a != null) main.postDelayed(() -> dumpButtons("trace started", a), 300L);
        startSampler();
        return file != null ? file.getAbsolutePath() : null;
    }

    public static String path() { return file != null ? file.getAbsolutePath() : null; }

    /** shares the tail of the latest log as plain text (chooser) */
    public static void shareLatest(Activity a) {
        try {
            flush();
            main.postDelayed(() -> {
                try {
                    File f = file;
                    if (f == null || !f.exists()) { return; }
                    long len = f.length();
                    int n = (int) Math.min(len, 150_000L);
                    byte[] b = new byte[n];
                    try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.seek(len - n); r.readFully(b); }
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Joyboy player trace").putExtra(Intent.EXTRA_TEXT, new String(b, "UTF-8"));
                    a.startActivity(Intent.createChooser(i, "مشاركة لوج الحركة"));
                } catch (Throwable ignored) { }
            }, 400L);
        } catch (Throwable ignored) { }
    }

    // =====================================================================================  marks / lifecycle

    /** a named event from the player code (open / minimize / maximize / PiP / back …); the next motion segment is labelled with it */
    public static void mark(String what) {
        if (!enabled) return;
        pendingCause = what;
        pendingCauseAt = now();
        log("MARK  " + what, true);
    }

    private static void life(Activity a, String ev) {
        if (!enabled) return;
        log("LIFE  " + a.getClass().getSimpleName() + " " + ev, true);
    }

    // =====================================================================================  sampler

    private static void startSampler() {
        if (running) return;
        running = true;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (!enabled) { running = false; return; }
                try { onFrame(frameTimeNanos); } catch (Throwable ignored) { }
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private static long now() { return System.currentTimeMillis() - t0; }

    private static void onFrame(long nanos) {
        float dt = prevNanos == 0 ? 0f : (nanos - prevNanos) / 1e6f;
        prevNanos = nanos;
        Activity a = cur.get();
        Map<String, float[]> snap = a != null ? snapshot(a) : new LinkedHashMap<String, float[]>();
        String an = a != null ? a.getClass().getSimpleName() : "-";
        boolean actChanged = !an.equals(prevActName) && !prevActName.isEmpty();
        boolean changed = differs(prevSnap, snap) || actChanged;
        long t = now();
        if (changed) {
            if (!inSeg) beginSegment(t, snap, an);
            lastChangeMs = t;
        }
        if (inSeg) {
            frameNo++;
            segFrames++;
            if (dt > 0f) { segDts.add(dt); if (dt > segWorstDt) segWorstDt = dt; }
            StringBuilder flags = new StringBuilder();
            if (dt > 50f) { segStall++; segJank++; flags.append(" STALL"); }
            else if (dt > frameMs * 1.5f) { segJank++; flags.append(" JANK"); }
            StringBuilder line = new StringBuilder();
            line.append(String.format(Locale.US, "f%-4d dt=%5.1f", segFrames, dt));
            if (actChanged) line.append("  ACT ").append(prevActName).append("→").append(an);
            DisplayMetricsHolder dm = DisplayMetricsHolder.of(a);
            for (Map.Entry<String, float[]> e : snap.entrySet()) {
                float[] n = e.getValue();
                float[] p = prevSnap.get(e.getKey());
                line.append("\n        ").append(shortKey(e.getKey())).append(String.format(Locale.US, " %6.1f,%6.1f %6.1fx%-6.1f a=%.2f", n[0], n[1], n[2], n[3], n[4]));
                if (p == null) { line.append("  (appeared)"); continue; }
                float dx = n[0] - p[0], dy = n[1] - p[1], dw = n[2] - p[2], dh = n[3] - p[3], da = n[4] - p[4];
                line.append(String.format(Locale.US, "  Δ %+.1f,%+.1f %+.1f,%+.1f", dx, dy, dw, dh));
                float m = (float) (Math.hypot(dx, dy) + Math.hypot(dw, dh));
                float[] ls = lastStep.get(e.getKey());
                if (m > 0.5f || (ls != null && ls[2] > 0.5f)) {
                    if (m > 0.25f * dm.diag) { segTele++; line.append(" TELEPORT"); }
                    else if (ls != null) {
                        float avg = (ls[2] + ls[3] + ls[4]) / 3f;
                        if (avg > 1f && m > 3f * avg + 24f) { segJump++; line.append(" JUMP"); }
                        if ((dx * ls[0] < 0 && Math.abs(dx) > 2f && Math.abs(ls[0]) > 2f) || (dy * ls[1] < 0 && Math.abs(dy) > 2f && Math.abs(ls[1]) > 2f)) { segRev++; line.append(" REVERSE"); }
                    }
                }
                if (Math.abs(da) > 0.5f) { segAlpha++; line.append(" ALPHA-JUMP"); }
                if (m > 0.01f || ls == null) lastStep.put(e.getKey(), new float[]{dx, dy, m, ls != null ? ls[2] : m, ls != null ? ls[3] : m});
            }
            for (Map.Entry<String, float[]> p : prevSnap.entrySet()) if (!snap.containsKey(p.getKey())) line.append("\n        ").append(shortKey(p.getKey())).append("  (gone)");
            log(line.append(flags).toString(), false);
            if (!changed && t - lastChangeMs > END_IDLE_MS) endSegment();
        }
        prevSnap = snap;
        prevActName = an;
    }

    private static void beginSegment(long t, Map<String, float[]> snap, String act) {
        inSeg = true; segStartMs = t; lastChangeMs = t;
        segFrames = 0; segJank = 0; segStall = 0; segJump = 0; segRev = 0; segTele = 0; segAlpha = 0; segWorstDt = 0f;
        segDts.clear(); lastStep.clear();
        segStart = new LinkedHashMap<>(prevSnap);
        segCause = (!pendingCause.isEmpty() && t - pendingCauseAt < 1500L) ? pendingCause : "unknown (no mark)";
        log("──── MOTION START  cause=" + segCause + "  activity=" + act, true);
    }

    private static void endSegment() {
        if (!inSeg) return;
        inSeg = false;
        float avg = 0f, p95 = 0f;
        if (!segDts.isEmpty()) {
            float s = 0f; for (float d : segDts) s += d; avg = s / segDts.size();
            List<Float> c = new ArrayList<>(segDts); Collections.sort(c);
            p95 = c.get(Math.min(c.size() - 1, (int) (c.size() * 0.95f)));
        }
        long dur = lastChangeMs - segStartMs;
        StringBuilder v = new StringBuilder();
        if (segJank == 0 && segJump == 0 && segRev == 0 && segTele == 0 && segAlpha == 0) v.append("✅ SMOOTH");
        else {
            v.append("⚠ PROBLEMS:");
            if (segJank > 0) v.append(" jank=").append(segJank).append(segStall > 0 ? "(stall " + segStall + ")" : "");
            if (segJump > 0) v.append(" jump=").append(segJump);
            if (segRev > 0) v.append(" reverse=").append(segRev);
            if (segTele > 0) v.append(" teleport=").append(segTele);
            if (segAlpha > 0) v.append(" alpha-jump=").append(segAlpha);
        }
        log(String.format(Locale.US, "──── MOTION END  cause=%s  moved %dms  frames=%d  dt avg=%.1f p95=%.1f worst=%.1f  %s", segCause, dur, segFrames, avg, p95, segWorstDt, v), true);
        if (segJank > 0) log("   hint: late frames = main thread busy during the motion (layout / setLayoutParams / insets / decoding). Look at LIFE + MARK lines right before the first JANK.", true);
        if (segJump > 0) log("   hint: JUMP = position/size stepped instead of easing — usually a layout param change or rotation landing mid-animation.", true);
        if (segRev > 0) log("   hint: REVERSE = two animations fighting (one pulls back while another pushes).", true);
        if (segTele > 0) log("   hint: TELEPORT = the video appeared somewhere else in ONE frame — activity switch / texture re-attach, not an animation.", true);
        if (segAlpha > 0) log("   hint: ALPHA-JUMP = a surface popped in/out (no fade) — flash / white screen candidate.", true);
        flush();
    }

    // ---- what is a "video thing" ----

    private static boolean tracked(View v) {
        if (v instanceof TextureView || v instanceof SurfaceView) return true;
        String sn = v.getClass().getSimpleName();
        if (sn.equals("PinchZoomLayout")) return true;
        if (v.getId() != View.NO_ID) {
            String n = idName(v).toLowerCase(Locale.ROOT);
            if (n.contains("mini") && (n.contains("card") || n.contains("player") || n.contains("video"))) return true;
        }
        return false;
    }

    private static Map<String, float[]> snapshot(Activity a) {
        Map<String, float[]> m = new LinkedHashMap<>();
        View root = a.getWindow() != null ? a.getWindow().getDecorView() : null;
        if (root == null) return m;
        int[] idx = {0};
        walkTracked(root, m, a.getClass().getSimpleName(), idx);
        return m;
    }

    private static void walkTracked(View v, Map<String, float[]> out, String act, int[] idx) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (tracked(v) && v.getWidth() > 0 && v.getHeight() > 0) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float sx = 1f, sy = 1f, al = 1f;
            View c = v;
            while (true) {
                sx *= c.getScaleX(); sy *= c.getScaleY(); al *= c.getAlpha();
                if (c.getParent() instanceof View) c = (View) c.getParent(); else break;
            }
            out.put(act + ":" + v.getClass().getSimpleName() + "#" + idx[0]++ + (v.getId() != View.NO_ID ? "/" + idName(v) : ""),
                    new float[]{loc[0], loc[1], v.getWidth() * sx, v.getHeight() * sy, al});
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkTracked(g.getChildAt(i), out, act, idx);
        }
    }

    private static boolean differs(Map<String, float[]> a, Map<String, float[]> b) {
        if (a.size() != b.size()) return true;
        for (Map.Entry<String, float[]> e : b.entrySet()) {
            float[] p = a.get(e.getKey());
            if (p == null) return true;
            float[] n = e.getValue();
            for (int i = 0; i < 5; i++) if (Math.abs(n[i] - p[i]) > (i == 4 ? 0.004f : 0.3f)) return true;
        }
        return false;
    }

    private static String shortKey(String k) {
        int i = k.indexOf(':');
        return String.format(Locale.US, "%-34s", i >= 0 ? k.substring(i + 1) : k);
    }

    private static final class DisplayMetricsHolder {
        final float diag;
        DisplayMetricsHolder(float d) { diag = d; }
        static DisplayMetricsHolder of(Activity a) {
            try {
                android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
                return new DisplayMetricsHolder((float) Math.hypot(dm.widthPixels, dm.heightPixels));
            } catch (Throwable t) { return new DisplayMetricsHolder(2000f); }
        }
    }

    // =====================================================================================  touches

    /** call at the top of Activity.dispatchTouchEvent: logs the tap + the view it hit, and what appeared 450 ms later */
    public static void touch(Activity a, MotionEvent e) {
        if (!enabled || a == null) return;
        try {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                View hit = hit(a.getWindow().getDecorView(), (int) e.getRawX(), (int) e.getRawY());
                log(String.format(Locale.US, "TOUCH down (%.0f,%.0f)  hit=%s", e.getRawX(), e.getRawY(), hit != null ? describe(hit) : "— (no clickable view: gesture area)"), true);
                pendingCause = "tap@" + (int) e.getRawX() + "," + (int) e.getRawY() + (hit != null ? " " + describe(hit) : "");
                pendingCauseAt = now();
            } else if (act == MotionEvent.ACTION_UP) {
                long dur = e.getEventTime() - e.getDownTime();
                log(String.format(Locale.US, "TOUCH up   (%.0f,%.0f)  held %dms", e.getRawX(), e.getRawY(), dur), true);
                main.postDelayed(() -> { if (cur.get() == a) dumpDiff("after tap", a); }, 450L);
            }
        } catch (Throwable ignored) { }
    }

    private static View hit(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.01f) return null;
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View h = hit(g.getChildAt(i), x, y);
                if (h != null) return h;
            }
        }
        return (v.isClickable() || v.isLongClickable() || v instanceof android.widget.ImageButton) ? v : null;
    }

    // =====================================================================================  buttons map

    /** full list of the visible buttons: exact position, size, text — sorted top→bottom, then left→right */
    public static void dumpButtons(String why, Activity a) {
        if (!enabled || a == null) return;
        try {
            List<String[]> rows = collect(a.getWindow().getDecorView(), a.getResources().getDisplayMetrics());
            Set<String> keys = new HashSet<>();
            StringBuilder sb = new StringBuilder("BUTTONS (" + why + ")  " + a.getClass().getSimpleName() + "  count=" + rows.size());
            int n = 0;
            for (String[] r : rows) {
                keys.add(r[0]);
                if (n++ < MAX_DUMP_LINES) sb.append("\n   ").append(r[1]);
            }
            if (rows.size() > MAX_DUMP_LINES) sb.append("\n   … +").append(rows.size() - MAX_DUMP_LINES).append(" more");
            lastKeys = keys;
            log(sb.toString(), false);
            flush();
        } catch (Throwable ignored) { }
    }

    private static void dumpDiff(String why, Activity a) {
        try {
            List<String[]> rows = collect(a.getWindow().getDecorView(), a.getResources().getDisplayMetrics());
            Set<String> keys = new HashSet<>();
            StringBuilder sb = new StringBuilder();
            int nNew = 0;
            for (String[] r : rows) {
                keys.add(r[0]);
                if (!lastKeys.contains(r[0]) && nNew++ < 80) sb.append("\n   NEW   ").append(r[1]);
            }
            int gone = 0;
            for (String k : lastKeys) if (!keys.contains(k)) gone++;
            lastKeys = keys;
            if (nNew > 0 || gone > 0) { log("BUTTONS diff (" + why + ")  new=" + nNew + " gone=" + gone + sb, false); flush(); }
        } catch (Throwable ignored) { }
    }

    private static List<String[]> collect(View root, android.util.DisplayMetrics dm) {
        List<String[]> out = new ArrayList<>();
        walkButtons(root, dm, out);
        Collections.sort(out, (x, y) -> {
            int c = Integer.compare(Integer.parseInt(x[2]), Integer.parseInt(y[2]));
            return c != 0 ? c : Integer.compare(Integer.parseInt(x[3]), Integer.parseInt(y[3]));
        });
        return out;
    }

    private static void walkButtons(View v, android.util.DisplayMetrics dm, List<String[]> out) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.01f) return;
        boolean btn = v.isClickable() || v instanceof android.widget.ImageButton || v.getContentDescription() != null && v.isClickable();
        if (btn && v.getWidth() > 0 && v.getHeight() > 0) {
            int[] l = new int[2];
            v.getLocationOnScreen(l);
            float d = dm.density;
            int w = v.getWidth(), h = v.getHeight();
            String line = String.format(Locale.US, "%-44s x=%4d y=%4d %4dx%-4d px | %5.1fx%-5.1f dp | fromLeft=%4d fromRight=%4d a=%.2f%s",
                    describe(v), l[0], l[1], w, h, w / d, h / d, l[0], dm.widthPixels - (l[0] + w), v.getAlpha(), v.isEnabled() ? "" : " DISABLED");
            out.add(new String[]{describe(v) + "@" + l[0] + "," + l[1] + "," + w + "," + h, line, String.valueOf(l[1]), String.valueOf(l[0])});
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkButtons(g.getChildAt(i), dm, out);
        }
    }

    private static String describe(View v) {
        StringBuilder s = new StringBuilder();
        if (v.getId() != View.NO_ID) s.append(idName(v)).append('/');
        s.append(v.getClass().getSimpleName());
        CharSequence t = v instanceof TextView ? ((TextView) v).getText() : null;
        if (t != null && t.length() > 0) s.append(" \"").append(t.toString().replace('\n', ' ').trim(), 0, Math.min(14, t.toString().replace('\n', ' ').trim().length())).append('"');
        else if (v.getContentDescription() != null) s.append(" [").append(v.getContentDescription(), 0, Math.min(14, v.getContentDescription().length())).append(']');
        return s.toString();
    }

    private static String idName(View v) {
        int id = v.getId();
        String n = idNames.get(id);
        if (n == null) {
            try { n = v.getResources().getResourceEntryName(id); } catch (Throwable t) { n = "id" + id; }
            idNames.put(id, n);
        }
        return n;
    }

    // =====================================================================================  popups

    /** call right after PopupWindow.showAsDropDown: anchor + popup bounds, gap, side, and the items */
    public static void popup(String name, View anchor, PopupWindow pw) {
        if (!enabled || anchor == null || pw == null) return;
        pendingCause = "popup " + name; pendingCauseAt = now();
        main.postDelayed(() -> {
            try {
                View content = pw.getContentView();
                View decor = content.getRootView();
                int[] al = new int[2], pl = new int[2];
                anchor.getLocationOnScreen(al);
                decor.getLocationOnScreen(pl);
                android.util.DisplayMetrics dm = anchor.getResources().getDisplayMetrics();
                float d = dm.density;
                int aw = anchor.getWidth(), ah = anchor.getHeight(), pw0 = decor.getWidth(), ph = decor.getHeight();
                String side = pl[1] + ph <= al[1] ? "ABOVE" : pl[1] >= al[1] + ah ? "BELOW" : "BESIDE";
                int gap = side.equals("ABOVE") ? al[1] - (pl[1] + ph) : side.equals("BELOW") ? pl[1] - (al[1] + ah) : 0;
                int offX = (pl[0] + pw0 / 2) - (al[0] + aw / 2);
                StringBuilder sb = new StringBuilder("POPUP " + name);
                sb.append(String.format(Locale.US, "\n   anchor : %s  x=%d y=%d %dx%d px (%.0fx%.0f dp)", describe(anchor), al[0], al[1], aw, ah, aw / d, ah / d));
                sb.append(String.format(Locale.US, "\n   popup  : x=%d y=%d %dx%d px (%.0fx%.0f dp)  side=%s gap=%dpx (%.1fdp)  center-offset-from-anchor=%dpx", pl[0], pl[1], pw0, ph, pw0 / d, ph / d, side, gap, gap / d, offX));
                sb.append(String.format(Locale.US, "\n   inside screen: %s  (screen %dx%d)", (pl[0] >= 0 && pl[1] >= 0 && pl[0] + pw0 <= dm.widthPixels && pl[1] + ph <= dm.heightPixels) ? "yes" : "NO — clipped", dm.widthPixels, dm.heightPixels));
                List<String[]> rows = collect(decor, dm);
                int n = 0;
                for (String[] r : rows) if (n++ < 60) sb.append("\n   item   ").append(r[1]);
                if (rows.size() > 60) sb.append("\n   … +").append(rows.size() - 60);
                log(sb.toString(), false);
                flush();
            } catch (Throwable ignored) { }
        }, 180L);
    }

    // =====================================================================================  output

    private static void log(String s, boolean forward) {
        if (!enabled && !s.startsWith("TRACE OFF")) return;
        String line = String.format(Locale.US, "%7d | %s\n", now(), s);
        synchronized (buf) { buf.append(line); }
        if (forward) {
            Sink k = sink;
            if (k != null) { try { k.line("[trace] " + s); } catch (Throwable ignored) { } }
        }
    }

    private static void flush() {
        final String chunk;
        synchronized (buf) {
            if (buf.length() == 0) return;
            chunk = buf.toString();
            buf.setLength(0);
        }
        final File f = file;
        if (f == null) return;
        io.execute(() -> {
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(chunk.getBytes("UTF-8")); } catch (Throwable ignored) { }
        });
    }
}
