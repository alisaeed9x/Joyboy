package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
 *   <li><b>Windows (v953)</b>: every Dialog / bottom sheet / popup / panel window of the process (found through the global window list):
 *   open/close, WindowManager attrs (type, gravity, size, dim, animation style), exact bounds, the sheet panel inside it, who opened it, its items,
 *   per-frame motion of the window + panel, and taps inside it.</li>
 *   <li><b>TextureView transform (v953)</b>: setTransform() matrix (scale / translate / rotate), the transformed video rect on screen and how much of it is visible.</li>
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

    // ---- windows (dialogs / sheets / popups / panels) — v953 ----
    private static final class Win {
        int n;
        long openedAt;
        String label;
        String kind;
        WeakReference<View> root;
    }
    private static final Map<View, Win> wins = new WeakHashMap<>();
    private static int winSeq;
    private static java.lang.reflect.Method miGlobalViews;
    private static boolean miTried;
    private static java.lang.reflect.Field fmViews;
    private static Object wmg;
    private static boolean winEnumFailLogged;
    private static final Map<String, String> notes = new HashMap<>();   // per-frame extra text for a tracked key (matrix info …)
    private static String lastTapDesc = "";
    private static long lastTapAt;

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
            wins.clear();
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
        wins.clear(); winSeq = 0; notes.clear(); lastTapDesc = ""; lastTapAt = 0L; winEnumFailLogged = false;
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
        log("legend: Δ = change since previous frame · JANK = frame later than 1.5x vsync · STALL = >50ms · JUMP = step 3x bigger than recent · REVERSE = direction flipped · TELEPORT = moved >25% of screen in one frame · key suffix /xf = the video picture after the TextureView matrix (setTransform) · win-Dialog@n/window|panel = a Dialog/sheet/popup window and the panel inside it", false);
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
        List<View> winRoots = trackWindows(a);
        Map<String, float[]> snap = a != null ? snapshot(a, winRoots) : new LinkedHashMap<String, float[]>();
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
                if (n.length > 5 && Math.abs(n[5]) > 0.05f) line.append(String.format(Locale.US, " rot=%.1f°", n[5]));
                String note = notes.get(e.getKey());
                if (note != null) line.append("  ").append(note);
                if (p == null) { line.append("  (appeared)"); continue; }
                float dx = n[0] - p[0], dy = n[1] - p[1], dw = n[2] - p[2], dh = n[3] - p[3], da = n[4] - p[4];
                line.append(String.format(Locale.US, "  Δ %+.1f,%+.1f %+.1f,%+.1f", dx, dy, dw, dh));
                if (n.length > 5 && p.length > 5 && Math.abs(n[5] - p[5]) > 0.05f) line.append(String.format(Locale.US, " Δrot %+.1f°", n[5] - p[5]));
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

    private static Map<String, float[]> snapshot(Activity a, List<View> winRoots) {
        Map<String, float[]> m = new LinkedHashMap<>();
        notes.clear();
        View root = a != null && a.getWindow() != null ? a.getWindow().getDecorView() : null;
        if (root != null) {
            int[] idx = {0};
            walkTracked(root, m, a.getClass().getSimpleName(), idx);
        }
        // v953: Dialog / sheet / popup / panel windows are separate roots — not under the activity decor
        for (View r : winRoots) {
            Win w = wins.get(r);
            if (w == null || "Toast".equals(w.kind) || r.getWidth() <= 0 || r.getHeight() <= 0) continue;
            String lab = "win-" + w.label;
            float[] wr = rectOf(r);
            WindowManager.LayoutParams lp = wlp(r);
            if (lp != null) wr[4] *= lp.alpha;
            m.put(lab + ":window", wr);
            if (!w.kind.startsWith("Activity:")) {
                View p = panelOf(r);
                if (p != null && p != r) m.put(lab + ":panel", rectOf(p));
            }
            walkTracked(r, m, lab, new int[]{0});
        }
        return m;
    }

    /** on-screen {x, y, w, h, alpha, rotation} of a view, with the scale / alpha / rotation of all its ancestors applied */
    private static float[] rectOf(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float sx = 1f, sy = 1f, al = 1f, rot = 0f;
        View c = v;
        while (true) {
            sx *= c.getScaleX(); sy *= c.getScaleY(); al *= c.getAlpha(); rot += c.getRotation();
            if (c.getParent() instanceof View) c = (View) c.getParent(); else break;
        }
        return new float[]{loc[0], loc[1], v.getWidth() * sx, v.getHeight() * sy, al, rot};
    }

    private static void walkTracked(View v, Map<String, float[]> out, String act, int[] idx) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (tracked(v) && v.getWidth() > 0 && v.getHeight() > 0) {
            String key = act + ":" + v.getClass().getSimpleName() + "#" + idx[0]++ + (v.getId() != View.NO_ID ? "/" + idName(v) : "");
            float[] r = rectOf(v);
            out.put(key, r);
            if (v instanceof TextureView) textureXf((TextureView) v, key, r, out);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkTracked(g.getChildAt(i), out, act, idx);
        }
    }

    /**
     * v953: a TextureView's setTransform() matrix moves / scales / rotates the VIDEO inside the view without touching the view's own
     * bounds, so the view tree alone can't see it. This adds a "/xf" entry = where the picture really is (view bounds mapped through the matrix).
     */
    private static void textureXf(TextureView tv, String key, float[] r, Map<String, float[]> out) {
        try {
            Matrix m = new Matrix();
            tv.getTransform(m);
            float[] f = new float[9];
            m.getValues(f);
            float w = Math.max(1f, tv.getWidth()), h = Math.max(1f, tv.getHeight());
            RectF rc = new RectF(0f, 0f, w, h);
            m.mapRect(rc);
            float scx = (float) Math.hypot(f[Matrix.MSCALE_X], f[Matrix.MSKEW_Y]);
            float scy = (float) Math.hypot(f[Matrix.MSKEW_X], f[Matrix.MSCALE_Y]);
            float rot = (float) Math.toDegrees(Math.atan2(f[Matrix.MSKEW_Y], f[Matrix.MSCALE_X]));
            float kx = r[2] / w, ky = r[3] / h;   // ancestors' scale
            out.put(key + "/xf", new float[]{r[0] + rc.left * kx, r[1] + rc.top * ky, rc.width() * kx, rc.height() * ky, r[4], r[5] + rot});
            float vl = Math.max(0f, rc.left), vt = Math.max(0f, rc.top), vr = Math.min(w, rc.right), vb = Math.min(h, rc.bottom);
            float full = Math.max(1f, rc.width() * rc.height());
            float vis = Math.max(0f, vr - vl) * Math.max(0f, vb - vt);
            notes.put(key + "/xf", String.format(Locale.US, "[matrix %s scale %.3fx%.3f trans %.1f,%.1f rot %.1f° · picture visible %.0f%%]",
                    m.isIdentity() ? "identity" : "SET", scx, scy, f[Matrix.MTRANS_X], f[Matrix.MTRANS_Y], rot, 100f * vis / full));
        } catch (Throwable ignored) { }
    }

    private static boolean differs(Map<String, float[]> a, Map<String, float[]> b) {
        if (a.size() != b.size()) return true;
        for (Map.Entry<String, float[]> e : b.entrySet()) {
            float[] p = a.get(e.getKey());
            if (p == null) return true;
            float[] n = e.getValue();
            for (int i = 0, c = Math.min(n.length, p.length); i < c; i++) if (Math.abs(n[i] - p[i]) > (i == 4 ? 0.004f : i == 5 ? 0.2f : 0.3f)) return true;
        }
        return false;
    }

    private static String shortKey(String k) {
        if (k.startsWith("win-")) return String.format(Locale.US, "%-34s", k.replace(':', '/'));
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
        try { touchIn(a.getWindow().getDecorView(), "", e); } catch (Throwable ignored) { }
    }

    /** a touch that arrived in window `root` (activity decor, a Dialog / sheet window, a popup). `label` is empty for the activity itself. */
    private static void touchIn(View root, String label, MotionEvent e) {
        if (!enabled || root == null || e == null) return;
        int act = e.getActionMasked();
        String where = label.isEmpty() ? "" : "  win=" + label;
        if (act == MotionEvent.ACTION_DOWN) {
            int[] rl = new int[2];
            root.getLocationOnScreen(rl);
            View hit = hit(root, (int) e.getRawX() - rl[0], (int) e.getRawY() - rl[1]);
            String hd = hit != null ? describe(hit) : "— (no clickable view: gesture area)";
            log(String.format(Locale.US, "TOUCH down (%.0f,%.0f)%s  hit=%s", e.getRawX(), e.getRawY(), where, hd), true);
            String c = "tap@" + (int) e.getRawX() + "," + (int) e.getRawY() + (label.isEmpty() ? "" : " in " + label) + (hit != null ? " " + describe(hit) : "");
            pendingCause = c; pendingCauseAt = now();
            lastTapDesc = c; lastTapAt = now();
        } else if (act == MotionEvent.ACTION_UP) {
            long dur = e.getEventTime() - e.getDownTime();
            log(String.format(Locale.US, "TOUCH up   (%.0f,%.0f)%s  held %dms", e.getRawX(), e.getRawY(), where, dur), true);
            main.postDelayed(() -> { Activity c = cur.get(); if (c != null) dumpDiff("after tap", c); }, 450L);
        }
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
            List<String[]> rows = collectAll(a);
            Set<String> keys = new HashSet<>();
            StringBuilder sb = new StringBuilder("BUTTONS (" + why + ")  " + a.getClass().getSimpleName() + "  count=" + rows.size());
            String ws = windowsSummary(a);
            if (!ws.isEmpty()) sb.append("\n   windows open: ").append(ws);
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
            List<String[]> rows = collectAll(a);
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

    /** v953: the activity's buttons + the buttons inside every other window (Dialog / sheet / popup), prefixed with the window label */
    private static List<String[]> collectAll(Activity a) {
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View ad = a.getWindow().getDecorView();
        List<String[]> rows = collect(ad, dm);
        List<View> all = rootViews();
        if (all == null) return rows;
        for (View r : all) {
            if (r == null || r == ad || !r.isAttachedToWindow() || r.getVisibility() != View.VISIBLE) continue;
            Win w = wins.get(r);
            String tag = "[" + (w != null ? w.label : kindOf(r) + "@?") + "] ";
            for (String[] x : collect(r, dm)) rows.add(new String[]{tag + x[0], tag + x[1], x[2], x[3]});
        }
        return rows;
    }

    private static String windowsSummary(Activity a) {
        StringBuilder sb = new StringBuilder();
        try {
            List<View> all = rootViews();
            View ad = a.getWindow().getDecorView();
            if (all != null) for (View r : all) {
                if (r == null || r == ad || !r.isAttachedToWindow() || r.getVisibility() != View.VISIBLE) continue;
                Win w = wins.get(r);
                int[] l = new int[2];
                r.getLocationOnScreen(l);
                if (sb.length() > 0) sb.append(" · ");
                sb.append(w != null ? w.label : kindOf(r)).append(String.format(Locale.US, " (%d,%d %dx%d)", l[0], l[1], r.getWidth(), r.getHeight()));
            }
        } catch (Throwable ignored) { }
        return sb.toString();
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
        try { pw.setTouchInterceptor((v, ev) -> { try { touchIn(v.getRootView(), "Popup:" + name, ev); } catch (Throwable ignored) { } return false; }); } catch (Throwable ignored) { }   // v953: taps inside the popup
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

    // =====================================================================================  windows (v953)

    /** every window root of this process (Activity decors, Dialogs, bottom sheets, popups, toasts) — null if the platform blocks the lookup */
    @SuppressWarnings("unchecked")
    private static List<View> rootViews() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                if (!miTried) {
                    miTried = true;
                    try { miGlobalViews = Class.forName("android.view.inspector.WindowInspector").getMethod("getGlobalWindowViews"); } catch (Throwable ignored) { }
                }
                if (miGlobalViews != null) {
                    Object r = miGlobalViews.invoke(null);
                    if (r instanceof List) return new ArrayList<View>((List<View>) r);
                }
            }
        } catch (Throwable ignored) { }
        try {
            if (fmViews == null) {
                Class<?> c = Class.forName("android.view.WindowManagerGlobal");
                wmg = c.getMethod("getInstance").invoke(null);
                Field f = c.getDeclaredField("mViews");
                f.setAccessible(true);
                fmViews = f;
            }
            Object r = fmViews.get(wmg);
            if (r instanceof List) return new ArrayList<View>((List<View>) r);
        } catch (Throwable ignored) { }
        if (!winEnumFailLogged) {
            winEnumFailLogged = true;
            log("WINDOWS: this device blocks the window-list lookup — Dialog / sheet windows can't be tracked (only the activity window + MtBar popups)", true);
        }
        return null;
    }

    private static Activity activityOf(View v) {
        Context c = v.getContext();
        for (int i = 0; i < 10 && c instanceof android.content.ContextWrapper; i++) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return c instanceof Activity ? (Activity) c : null;
    }

    private static WindowManager.LayoutParams wlp(View r) {
        ViewGroup.LayoutParams lp = r.getLayoutParams();
        return lp instanceof WindowManager.LayoutParams ? (WindowManager.LayoutParams) lp : null;
    }

    private static String kindOf(View r) {
        Activity own = activityOf(r);
        if (own != null && own.getWindow() != null && own.getWindow().getDecorView() == r) return "Activity:" + own.getClass().getSimpleName();
        if (r.getClass().getSimpleName().contains("Popup")) return "Popup";
        WindowManager.LayoutParams lp = wlp(r);
        int t = lp != null ? lp.type : -1;
        if (t == 2 || t == 1003) return "Dialog";
        if (t == 1000 || t == 1001 || t == 1002 || t == 1005) return "Panel";
        if (t == 2005) return "Toast";
        return "Win" + t;
    }

    private static String typeName(int t) {
        switch (t) {
            case 1: return "APPLICATION_BASE(1)";
            case 2: return "APPLICATION(2)";
            case 1000: return "APPLICATION_PANEL(1000)";
            case 1001: return "APPLICATION_MEDIA(1001)";
            case 1002: return "APPLICATION_SUB_PANEL(1002)";
            case 1003: return "APPLICATION_ATTACHED_DIALOG(1003)";
            case 1005: return "APPLICATION_ABOVE_SUB_PANEL(1005)";
            case 2005: return "TOAST(2005)";
            case 2038: return "APPLICATION_OVERLAY(2038)";
            default: return String.valueOf(t);
        }
    }

    private static String gravStr(int g) {
        int v = g & android.view.Gravity.VERTICAL_GRAVITY_MASK, h = g & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
        String vs = v == android.view.Gravity.TOP ? "TOP" : v == android.view.Gravity.BOTTOM ? "BOTTOM" : v == android.view.Gravity.CENTER_VERTICAL ? "CENTER_V" : v == android.view.Gravity.FILL_VERTICAL ? "FILL_V" : "-";
        String hs = h == android.view.Gravity.LEFT ? "LEFT" : h == android.view.Gravity.RIGHT ? "RIGHT" : h == android.view.Gravity.CENTER_HORIZONTAL ? "CENTER_H" : h == android.view.Gravity.FILL_HORIZONTAL ? "FILL_H" : "-";
        return vs + "|" + hs + " (0x" + Integer.toHexString(g) + ")";
    }

    private static String sizeStr(int s) { return s == -1 ? "MATCH" : s == -2 ? "WRAP" : s + "px"; }

    private static String flagsStr(int f) {
        StringBuilder s = new StringBuilder();
        if ((f & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) s.append("DIM ");
        if ((f & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) s.append("NOT_FOCUSABLE ");
        if ((f & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0) s.append("NOT_TOUCH_MODAL ");
        if ((f & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) s.append("NOT_TOUCHABLE ");
        if ((f & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0) s.append("WATCH_OUTSIDE ");
        if ((f & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0) s.append("LAYOUT_IN_SCREEN ");
        if ((f & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0) s.append("LAYOUT_NO_LIMITS ");
        if ((f & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) s.append("FULLSCREEN ");
        return s.toString().trim();
    }

    private static String animName(View r, int id) {
        if (id == 0) return "none set (theme default window animation — the SYSTEM animates the window; not measurable inside the app)";
        try { return r.getResources().getResourceEntryName(id) + " (system-animated window; not measurable inside the app)"; } catch (Throwable t) { return "0x" + Integer.toHexString(id); }
    }

    /** the first sizeable descendant that is smaller than the window (= the visible sheet / card inside a bigger window); else the window itself */
    private static View panelOf(View root) {
        if (!(root instanceof ViewGroup)) return root;
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw <= 0 || rh <= 0) return root;
        ArrayList<View> q = new ArrayList<>();
        ArrayList<Integer> dq = new ArrayList<>();
        q.add(root); dq.add(0);
        for (int i = 0; i < q.size() && i < 120; i++) {
            View v = q.get(i);
            int d = dq.get(i);
            if (v != root && v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0
                    && (v.getWidth() < rw * 0.98f || v.getHeight() < rh * 0.98f)
                    && (float) v.getWidth() * v.getHeight() >= 0.04f * rw * rh) return v;
            if (d < 6 && v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int k = 0; k < g.getChildCount(); k++) { q.add(g.getChildAt(k)); dq.add(d + 1); }
            }
        }
        return root;
    }

    /** called every frame: opens / closes windows, returns the roots of all visible non-activity windows */
    private static List<View> trackWindows(Activity a) {
        List<View> out = new ArrayList<>();
        List<View> all = rootViews();
        if (all == null) return out;
        View ad = (a != null && a.getWindow() != null) ? a.getWindow().getDecorView() : null;
        Set<View> present = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        for (View r : all) {
            if (r == null || r == ad || !r.isAttachedToWindow() || r.getVisibility() != View.VISIBLE) continue;
            present.add(r);
            out.add(r);
            if (!wins.containsKey(r)) {
                Win w = new Win();
                w.n = ++winSeq;
                w.kind = kindOf(r);
                w.label = w.kind + "@" + w.n;
                w.openedAt = now();
                w.root = new WeakReference<>(r);
                wins.put(r, w);
                windowOpened(r, w);
            }
        }
        for (Map.Entry<View, Win> e : new ArrayList<>(wins.entrySet())) {
            View k = e.getKey();
            if (k == null || !present.contains(k)) {
                windowClosed(e.getValue());
                if (k != null) wins.remove(k);
            }
        }
        return out;
    }

    private static void windowOpened(final View r, final Win w) {
        try {
            WindowManager.LayoutParams lp = wlp(r);
            boolean recentTap = !lastTapDesc.isEmpty() && now() - lastTapAt < 1500L;
            log("WINDOW OPEN   " + w.label + "  type=" + typeName(lp != null ? lp.type : -1) + "  opened by: " + (recentTap ? lastTapDesc : "unknown (no tap in the last 1.5s)")
                    + (w.openedAt < 400L ? "  (already open when tracing started)" : ""), true);
            if (!"Toast".equals(w.kind) && !w.kind.startsWith("Activity:")) { pendingCause = "window " + w.label; pendingCauseAt = now(); }
            if (w.kind.equals("Dialog")) hookWindowTouches(r, w.label);
            main.postDelayed(() -> windowDetail(w), 150L);
        } catch (Throwable ignored) { }
    }

    private static void windowClosed(Win w) {
        try {
            log("WINDOW CLOSE  " + w.label + "  lived " + (now() - w.openedAt) + "ms", true);
            if (!"Toast".equals(w.kind) && !w.kind.startsWith("Activity:")) { pendingCause = "window closed " + w.label; pendingCauseAt = now(); }
        } catch (Throwable ignored) { }
    }

    /** layout is done 150 ms after the window appears: exact bounds, WindowManager attrs, the panel inside, its items */
    private static void windowDetail(Win w) {
        try {
            View r = w.root.get();
            if (!enabled) return;
            if (r == null || !r.isAttachedToWindow()) { log("WINDOW " + w.label + "  (closed before its first layout)", false); return; }
            WindowManager.LayoutParams lp = wlp(r);
            android.util.DisplayMetrics dm = r.getResources().getDisplayMetrics();
            float d = dm.density;
            int[] l = new int[2];
            r.getLocationOnScreen(l);
            int ww = r.getWidth(), wh = r.getHeight();
            StringBuilder sb = new StringBuilder("WINDOW ").append(w.label).append("  details");
            sb.append("\n   class  : ").append(r.getClass().getName());
            if (lp != null) {
                sb.append("\n   attrs  : type=").append(typeName(lp.type)).append("  gravity=").append(gravStr(lp.gravity))
                        .append("  size=").append(sizeStr(lp.width)).append("x").append(sizeStr(lp.height)).append("  offset=").append(lp.x).append(",").append(lp.y)
                        .append("  dim=").append((lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0 ? lp.dimAmount : 0f)
                        .append("  alpha=").append(lp.alpha).append("  softInput=0x").append(Integer.toHexString(lp.softInputMode))
                        .append("  flags=[").append(flagsStr(lp.flags)).append("]");
                sb.append("\n   anim   : ").append(animName(r, lp.windowAnimations));
                CharSequence t = lp.getTitle();
                if (t != null && t.length() > 0) sb.append("\n   title  : ").append(t);
            }
            sb.append(String.format(Locale.US, "\n   window : x=%d y=%d %dx%d px (%.0fx%.0f dp)  fromLeft=%d fromRight=%d fromTop=%d fromBottom=%d",
                    l[0], l[1], ww, wh, ww / d, wh / d, l[0], dm.widthPixels - (l[0] + ww), l[1], dm.heightPixels - (l[1] + wh)));
            boolean inside = l[0] >= 0 && l[1] >= 0 && l[0] + ww <= dm.widthPixels && l[1] + wh <= dm.heightPixels;
            sb.append(String.format(Locale.US, "\n   inside screen: %s  (screen %dx%d, window covers %.0f%% of it)", inside ? "yes" : "NO — clipped / off-screen",
                    dm.widthPixels, dm.heightPixels, 100f * ww * wh / Math.max(1f, (float) dm.widthPixels * dm.heightPixels)));
            View p = panelOf(r);
            if (p != null && p != r) {
                int[] pl = new int[2];
                p.getLocationOnScreen(pl);
                int pw0 = p.getWidth(), ph = p.getHeight();
                sb.append(String.format(Locale.US, "\n   panel  : %s  x=%d y=%d %dx%d px (%.0fx%.0f dp)  fromLeft=%d fromRight=%d fromTop=%d fromBottom=%d",
                        describe(p), pl[0], pl[1], pw0, ph, pw0 / d, ph / d, pl[0], dm.widthPixels - (pl[0] + pw0), pl[1], dm.heightPixels - (pl[1] + ph)));
            } else {
                sb.append("\n   panel  : the window itself (WRAP_CONTENT sheet / popup)");
            }
            boolean recentTap = !lastTapDesc.isEmpty() && w.openedAt - lastTapAt < 1500L && w.openedAt >= lastTapAt;
            sb.append("\n   opened by: ").append(recentTap ? lastTapDesc : "unknown (no tap right before)");
            List<String[]> rows = collect(r, dm);
            int n = 0;
            for (String[] x : rows) if (n++ < 60) sb.append("\n   item   ").append(x[1]);
            if (rows.size() > 60) sb.append("\n   … +").append(rows.size() - 60);
            log(sb.toString(), false);
            flush();
        } catch (Throwable ignored) { }
    }

    /**
     * Taps inside a Dialog / sheet never reach Activity.dispatchTouchEvent. The dialog's Window.Callback is wrapped (java.lang.reflect.Proxy,
     * everything is delegated) so TOUCH lines + the "after tap" button diff also work there. Best effort: if the platform blocks the lookup, nothing changes.
     */
    private static void hookWindowTouches(View root, String label) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = root.getClass(); c != null && c != Object.class && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField("mWindow"); } catch (NoSuchFieldException ignored) { }
            }
            if (f == null) return;
            f.setAccessible(true);
            Object wo = f.get(root);
            if (!(wo instanceof Window)) return;
            Window win = (Window) wo;
            Window.Callback orig = win.getCallback();
            if (orig == null) return;
            if (java.lang.reflect.Proxy.isProxyClass(orig.getClass()) && java.lang.reflect.Proxy.getInvocationHandler(orig) instanceof TouchProxy) return;
            Window.Callback p = (Window.Callback) java.lang.reflect.Proxy.newProxyInstance(TouchProxy.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class}, new TouchProxy(orig, root, label));
            win.setCallback(p);
        } catch (Throwable ignored) { }
    }

    private static final class TouchProxy implements java.lang.reflect.InvocationHandler {
        private final Object orig;
        private final WeakReference<View> root;
        private final String label;

        TouchProxy(Object orig, View root, String label) {
            this.orig = orig;
            this.root = new WeakReference<>(root);
            this.label = label;
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) throws Throwable {
            if (enabled && args != null && args.length == 1 && args[0] instanceof MotionEvent && "dispatchTouchEvent".equals(m.getName())) {
                View r = root.get();
                if (r != null) { try { touchIn(r, label, (MotionEvent) args[0]); } catch (Throwable ignored) { } }
            }
            try {
                return m.invoke(orig, args);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause() != null ? ex.getCause() : ex;
            }
        }
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
