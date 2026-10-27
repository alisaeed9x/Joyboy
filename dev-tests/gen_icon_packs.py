#!/usr/bin/env python3
"""
(v598 م3) بيولّد الباكات المدمجة (Outline / Filled / Duotone / Rounded) كـ zip (pack.json + svg/*.svg)
من الأيقونات الافتراضية في IconDefs.kt، ويحطهم في app/src/main/assets/iconpacks/.
تشغيل (من جذر المشروع):  python3 dev-tests/gen_icon_packs.py
نفس الناتج دايمًا (ترتيب ثابت + وقت صفر في الـzip) فمفيش فروق وهمية في git.
اختياري: --preview بيرسم صفحة PNG لكل باك في /tmp/iconpack_preview (محتاج cairosvg).
"""
import json, os, re, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFS = os.path.join(ROOT, 'app/src/main/java/com/tttt/subtitler/IconDefs.kt')
OUT = os.path.join(ROOT, 'app/src/main/assets/iconpacks')

# ---------- قراءة IconDefs.kt ----------
def load_defs():
    txt = open(DEFS, encoding='utf-8').read()
    body = txt[txt.index('val DEF: Map<String, Array<String>> = mapOf('):]
    body = body[:body.index('\n    )\n')]
    defs = {}
    i = 0
    key = re.compile(r'"([^"\\]+)"\s+to\s+arrayOf\(')
    while True:
        m = key.search(body, i)
        if not m: break
        j = m.end(); layers = []
        while True:                                   # سلاسل نصية لحد القوس المقفول
            while body[j] in ' ,\n\r\t': j += 1
            if body[j] == ')': j += 1; break
            if body[j] != '"': raise SystemExit('تنسيق غير متوقع عند ' + body[j:j + 40])
            k = j + 1
            while body[k] != '"': k += 2 if body[k] == '\\' else 1
            layers.append(body[j + 1:k]); j = k + 1
        defs[m.group(1)] = layers
        i = j
    return defs

class L:
    def __init__(self, spec):
        k, c, a, d = spec.split('|', 3)
        self.fill = 'F' in k; self.stroke = 'S' in k
        self.color = None if c == '_' else c      # AARRGGBB أو None = لون النص
        self.alpha = float(a); self.d = d

    def bbox(self):
        nums = [float(x) for x in re.findall(r'-?\d+\.?\d*', self.d)]
        xs = nums[0::2]; ys = nums[1::2]
        return min(xs), min(ys), max(xs), max(ys)

    def closed(self):
        subs = [s for s in re.split(r'(?=M)', self.d) if s.strip()]
        return bool(subs) and all(s.rstrip().endswith('Z') for s in subs)

    def tiny(self):
        x0, y0, x1, y1 = self.bbox()
        return (x1 - x0) <= 5.2 and (y1 - y0) <= 5.2

    def contains(self, o):
        a = self.bbox(); b = o.bbox()
        return a[0] <= b[0] + .01 and a[1] <= b[1] + .01 and a[2] >= b[2] - .01 and a[3] >= b[3] - .01

def n(v):
    s = ('%.3f' % v).rstrip('0').rstrip('.')
    return s or '0'

def semantic(layers):
    """أيقونة حالة ملوّنة (✅ ❌ ⚠ …): فيها تعبئة بلون ثابت كامل — بتفضل زي الأصل في كل الباكات"""
    return any(l.color and l.fill and l.alpha >= 0.99 for l in layers)

def el(l, fill, stroke, fa, sa, sw):
    col = 'currentColor' if l.color is None else '#' + l.color[2:].upper()
    a = ['d="%s"' % l.d, 'fill="%s"' % (col if fill else 'none')]
    if fill and fa < 0.999: a.append('fill-opacity="%s"' % n(fa))
    if stroke:
        a += ['stroke="%s"' % col, 'stroke-width="%s"' % n(sw), 'stroke-linecap="round"', 'stroke-linejoin="round"']
        if sa < 0.999: a.append('stroke-opacity="%s"' % n(sa))
    a.append('fill-rule="evenodd"')
    return '  <path ' + ' '.join(a) + '/>'

def svg(lines):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">\n' + '\n'.join(lines) + '\n</svg>\n'

# ---------- الأساليب ----------
def style_default(layers, sw):
    return [el(l, l.fill, l.stroke, l.alpha, l.alpha, sw) for l in layers]

def style_outline(layers):
    """خطوط بس: كل تعبئة كبيرة تتحوّل لحدود؛ التعبئة الباهتة الزخرفية بتتشال؛ النقط الصغيرة بتفضل مليانة"""
    if semantic(layers): return style_default(layers, 2.2)
    has_stroke = any(l.stroke for l in layers)
    out = []
    for l in layers:
        if l.fill and not l.stroke:
            if l.tiny(): out.append(el(l, True, False, 1.0, 1.0, 1.9)); continue
            if l.alpha < 0.6 and has_stroke: continue          # تعبئة زخرفية فوق خط موجود
            out.append(el(l, False, True, 1.0, 1.0, 1.9)); continue
        if l.fill and l.stroke:
            out.append(el(l, False, True, 1.0 if l.alpha >= .99 else l.alpha, 1.0 if l.alpha >= .99 else l.alpha, 1.9)); continue
        out.append(el(l, False, True, l.alpha, l.alpha, 1.9))
    return out

def style_filled(layers):
    """مجسّم: الأشكال المقفولة بتتعبّى بالكامل — إلا لو فوقها تفاصيل مرسومة (علامة ✓ جوه مربع مثلًا) فبتفضل خط غليظ + جسم خفيف"""
    if semantic(layers): return style_default(layers, 2.2)
    out = []
    for i, l in enumerate(layers):
        on_top = any(l.contains(layers[j]) for j in range(i + 1, len(layers)))   # فيه تفاصيل مرسومة فوقه؟
        if l.fill and not l.stroke:                                      # مليان أصلًا (أشرطة/نقط): من غير حدود عشان الأشرطة القريبة ماتلزقش
            out.append(el(l, True, False, l.alpha, l.alpha, 2.2))
        elif l.closed() and not l.tiny() and not on_top:
            out.append(el(l, True, True, 1.0, 1.0, 2.2))            # جسم مليان
        elif l.closed() and not l.tiny() and on_top:
            out.append(el(l, True, True, 0.2, 1.0, 2.6))            # جسم خفيف + خط غليظ (التفاصيل فوقه تفضل باينة)
        else:
            out.append(el(l, l.fill, l.stroke, l.alpha, l.alpha, 2.6 if l.stroke else 2.2))
    return out

def style_duotone(layers):
    """نغمتين: الجسم بنفس اللون بشفافية ~28% + الخط بالكامل"""
    if semantic(layers): return style_default(layers, 2.2)
    out = []
    for l in layers:
        if l.tiny() and l.fill and not l.stroke:
            out.append(el(l, True, False, 1.0, 1.0, 2.2)); continue
        if l.fill and not l.stroke:                                      # مليان خالص → جسم باهت + حدود
            out.append(el(l, True, True, 0.3, 1.0, 2.0)); continue
        if l.fill and l.stroke:
            out.append(el(l, True, True, 0.3, 1.0, 2.2)); continue
        if l.closed() and not l.tiny() and l.alpha >= .99:               # خط مقفول → جسم باهت تحته
            out.append(el(l, True, True, 0.3, 1.0, 2.2)); continue
        out.append(el(l, False, True, l.alpha, l.alpha, 2.2))
    return out

def style_rounded(layers):
    """خط أغلظ بأطراف وزوايا مدوّرة (cornerRadius في pack.json بيدوّر الزوايا الحادة وقت الرسم)"""
    if semantic(layers): return style_default(layers, 2.8)
    return [el(l, l.fill, l.stroke, l.alpha, l.alpha, 2.8) for l in layers]

PACKS = [
    ('outline', 'Outline — خطوط رفيعة', style_outline, 0.0, 'خطوط بس بسُمك خفيف'),
    ('filled', 'Filled — مجسّم', style_filled, 0.0, 'أشكال مليانة وخط غليظ'),
    ('duotone', 'Duotone — نغمتين', style_duotone, 0.0, 'جسم باهت + خط كامل بنفس اللون'),
    ('rounded', 'Rounded — مدوّر', style_rounded, 2.0, 'خط أغلظ وزوايا مدوّرة'),
]

def build(defs):
    os.makedirs(OUT, exist_ok=True)
    names = sorted(k for k in defs if '@' not in k)
    made = {}
    for pid, title, fn, corner, desc in PACKS:
        mf = {'id': pid, 'name': title, 'version': 1, 'author': 'Joyboy', 'description': desc,
              'style': {'cornerRadius': corner, 'strokeWidth': 2.2}}
        path = os.path.join(OUT, pid + '.zip')
        files = {}
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
            def put(name, data):
                zi = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, data)
            put('pack.json', json.dumps(mf, ensure_ascii=False, indent=2))
            for nm in names:
                layers = [L(s) for s in defs[nm]]
                content = svg(fn(layers))
                files[nm] = content
                put('svg/%s.svg' % nm, content)
        made[pid] = files
        print('%-8s %3d icons  %6d bytes' % (pid, len(names), os.path.getsize(path)))
    return made

def preview(made, defs):
    try:
        import cairosvg
    except Exception:
        print('cairosvg مش موجود — ما اتعملش preview'); return
    out = '/tmp/iconpack_preview'; os.makedirs(out, exist_ok=True)
    names = sorted(k for k in defs if '@' not in k)
    cols = 14; cell = 64
    for pid, files in made.items():
        rows = (len(names) + cols - 1) // cols
        parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d"><rect width="100%%" height="100%%" fill="#14161c"/>' % (cols * cell, rows * cell)]
        for i, nm in enumerate(names):
            body = files[nm].split('\n', 1)[1].rsplit('</svg>', 1)[0].replace('currentColor', '#f1f3f8')
            x = (i % cols) * cell + 8; y = (i // cols) * cell + 8
            parts.append('<g transform="translate(%d,%d) scale(2)">%s</g>' % (x, y, body))
        parts.append('</svg>')
        cairosvg.svg2png(bytestring=''.join(parts).encode('utf-8'), write_to=os.path.join(out, pid + '.png'))
    print('preview →', out)

if __name__ == '__main__':
    defs = load_defs()
    print('أيقونات أساسية:', len([k for k in defs if '@' not in k]), '| كل المفاتيح:', len(defs))
    made = build(defs)
    if '--preview' in sys.argv: preview(made, defs)
