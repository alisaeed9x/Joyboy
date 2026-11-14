package com.tttt.subtitler

/**
 * (v995) CSS صفحة HTML لمذكرة المذاكرة (LectureDoc.buildHtml). كان ناقص من v993 فالبناء بيفشل: Unresolved reference 'LectureCss'.
 * بيتحط جوه <style> في الصفحة. فاتح/غامق تلقائي، RTL، وبيطبع كويس.
 */
object LectureCss {
    const val HTML = """
:root{--teal:#0f766e;--teal-dark:#115e59;--teal-soft:#e6f4f1;--ink:#1f2937;--muted:#6b7280;--bg:#f3f6f6;--paper:#ffffff;--line:#d7e3e1;--red:#c0392b;--warn-bg:#fdecea;--warn-line:#e7a39b;--note-bg:#fff8e1;--note-line:#f2d27a;--fn-bg:#f4f8f8}
@media (prefers-color-scheme: dark){:root{--teal:#2dd4bf;--teal-dark:#5eead4;--teal-soft:#10302d;--ink:#e5e7eb;--muted:#9ca3af;--bg:#0f1514;--paper:#161d1c;--line:#27403d;--red:#ff7b6b;--warn-bg:#3a1f1c;--warn-line:#7a3a33;--note-bg:#2e2812;--note-line:#6b5a1c;--fn-bg:#1b2423}}
*{box-sizing:border-box}
html,body{margin:0;padding:0}
body{background:var(--bg);color:var(--ink);font-family:"Segoe UI","Noto Naskh Arabic","Noto Sans Arabic",Tahoma,Arial,sans-serif;line-height:1.9;font-size:16px;-webkit-text-size-adjust:100%}
.sheet{max-width:860px;margin:0 auto;padding:18px 16px 48px;background:var(--paper)}
.cover{text-align:center;padding:26px 14px 18px;border-radius:18px;background:linear-gradient(135deg,var(--teal),var(--teal-dark));color:#fff;margin-bottom:14px}
.cover h1{margin:0 0 6px;font-size:1.6em;line-height:1.5;color:#fff}
.cover .sub{margin:0 0 10px;opacity:.9;font-size:1em}
.badge{display:inline-block;padding:4px 14px;border-radius:999px;background:rgba(255,255,255,.18);font-size:.82em;font-weight:700}
.notice{margin:10px 0;padding:10px 14px;border-radius:12px;background:var(--note-bg);border:1px solid var(--note-line);font-size:.92em}
.notice.warn{background:var(--warn-bg);border-color:var(--warn-line)}
.toc{margin:14px 0;padding:12px 16px;border-radius:14px;background:var(--teal-soft);border:1px solid var(--line)}
.toc h3{margin:0 0 6px;color:var(--teal-dark);font-size:1.05em}
.toc ol{margin:0;padding-inline-start:22px}
.toc li{margin:2px 0}
.toc a{color:var(--teal-dark);text-decoration:none;font-weight:600}
.toc a:hover{text-decoration:underline}
.divider{display:flex;align-items:center;justify-content:center;margin:22px 0 10px}
.divider:before,.divider:after{content:"";flex:1;height:1px;background:var(--line)}
.diamond{width:10px;height:10px;margin:0 10px;background:var(--teal);transform:rotate(45deg);border-radius:2px}
.section{margin:0 0 8px;scroll-margin-top:12px}
.section-title{display:flex;align-items:center;gap:10px;margin:6px 0 10px;font-size:1.25em;line-height:1.5;color:var(--teal-dark)}
.num{display:inline-flex;align-items:center;justify-content:center;min-width:30px;height:30px;padding:0 6px;border-radius:50%;background:var(--teal);color:#fff;font-size:.8em;font-weight:800}
ol.points{margin:6px 0 10px;padding-inline-start:26px}
ol.points>li{margin:5px 0}
.extra{margin:10px 0;padding:10px 14px;border-radius:12px;background:var(--fn-bg);border-inline-start:4px solid var(--teal)}
.lbl{display:block;margin-bottom:4px;font-weight:800;color:var(--teal-dark);font-size:.92em}
.medbox{margin:10px 0;padding:10px 14px;border-radius:12px;background:var(--warn-bg);border:1px solid var(--warn-line)}
.medbox .lbl{color:var(--red)}
.underline-note{color:var(--red);text-decoration:underline;text-underline-offset:3px}
.term{font-weight:700;color:var(--teal-dark)}
.star{color:var(--red);font-weight:800;font-size:.8em;margin-inline-start:2px}
figure{margin:12px 0;text-align:center}
figure img{max-width:100%;height:auto;border-radius:12px;border:1px solid var(--line)}
figcaption{margin-top:4px;font-size:.82em;color:var(--muted)}
figcaption.src{font-style:italic}
.footnotes{margin:12px 0;padding:10px 14px;border-radius:12px;background:var(--fn-bg);border:1px dashed var(--line);font-size:.9em}
.fn-title{display:block;margin-bottom:4px;font-weight:800;color:var(--teal-dark)}
.footnotes ol{margin:0;padding-inline-start:20px;list-style:none}
.fn-star{color:var(--red);font-weight:800}
.mindmap{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;margin:10px 0}
.mindmap .box{padding:10px 12px;border-radius:14px;background:var(--teal-soft);border:1px solid var(--line)}
.mindmap .box:nth-child(4n+2){background:#fdeee8}
.mindmap .box:nth-child(4n+3){background:#eaf7ea}
.mindmap .box:nth-child(4n+4){background:#f5eefb}
@media (prefers-color-scheme: dark){.mindmap .box:nth-child(n){background:var(--teal-soft)}}
.mindmap h4{margin:0 0 4px;color:var(--teal-dark);font-size:1em}
.mindmap ul{margin:0;padding-inline-start:18px;font-size:.92em}
table{width:100%;border-collapse:collapse;margin:8px 0 14px;font-size:.92em;display:block;overflow-x:auto}
th,td{border:1px solid var(--line);padding:7px 10px;vertical-align:top;text-align:start}
th{background:var(--teal);color:#fff;white-space:nowrap}
tr:nth-child(even) td{background:var(--fn-bg)}
.term-cell{font-weight:700;color:var(--teal-dark);white-space:nowrap}
.qa{margin:8px 0;padding:10px 14px;border-radius:12px;border:1px solid var(--line);background:var(--fn-bg)}
.qa .q{font-weight:800;color:var(--teal-dark);margin-bottom:3px}
.qa .a{color:var(--ink)}
.end-mark{margin:26px 0 0;text-align:center;color:var(--muted);letter-spacing:6px}
@media print{body{background:#fff}.sheet{max-width:none;padding:0}.cover{background:#0f766e!important;-webkit-print-color-adjust:exact;print-color-adjust:exact}.section,.qa,.medbox,figure{break-inside:avoid}}
"""
}
