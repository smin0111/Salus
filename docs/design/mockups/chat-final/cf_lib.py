"""채팅 완성 시안 공통 부품: 아이콘, 음식 일러스트, 배너, 꼬리표, CSS."""

P = {
    'plus': '<path d="M12 5v14M5 12h14"/>',
    'up': '<path d="M12 19V5M6 11l6-6 6 6"/>',
    'lock': '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 018 0v3"/>',
    'heart': '<path d="M12 20s-7-4.4-7-10a4 4 0 017-2.7A4 4 0 0119 10c0 5.6-7 10-7 10z"/>',
    'heartf': '<path d="M12 20s-7-4.4-7-10a4 4 0 017-2.7A4 4 0 0119 10c0 5.6-7 10-7 10z" fill="currentColor"/>',
    'cal': '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
    'copy': '<rect x="8.5" y="8.5" width="11" height="11" rx="2.5"/><path d="M15.5 8.5V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7.5a2 2 0 002 2h2.5"/>',
    'vol': '<path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z"/><path d="M15.5 9a4 4 0 010 6M18.5 6.5a7.5 7.5 0 010 11"/>',
    'share': '<path d="M12 3.5v11M7.5 8L12 3.5 16.5 8"/><path d="M5 12.5V18a2 2 0 002 2h10a2 2 0 002-2v-5.5"/>',
    'pen': '<path d="M4 20h4.5L19 9.5 14.5 5 4 15.5z"/><path d="M13 6.5l4.5 4.5"/>',
    'trash': '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13"/>',
    'menu': '<path d="M4 7h16M4 12h16M4 17h10"/>',
    'x': '<path d="M6 6l12 12M18 6L6 18"/>',
    'check': '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    'alert': '<path d="M12 6.5v7"/><circle cx="12" cy="17.3" r=".9" fill="currentColor" stroke="none"/>',
    'q': '<path d="M9.3 9.3a2.7 2.7 0 115.2 1.1c-.6 1.1-2.5 1.6-2.5 3.2"/><circle cx="12" cy="17.3" r=".9" fill="currentColor" stroke="none"/>',
    'minus': '<path d="M7 12h10"/>',
    'clock': '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    'users': '<circle cx="9" cy="9" r="3"/><path d="M3.5 19c.6-3 2.8-4.6 5.5-4.6s4.9 1.6 5.5 4.6"/><path d="M15.5 6.3a3 3 0 010 5.4M17.5 14.6c1.6.6 2.7 2.1 3 4.4"/>',
    'user': '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20c.8-3.6 3.6-5.5 7-5.5s6.2 1.9 7 5.5"/>',
    'flame': '<path d="M12 21c-3.9 0-6.5-2.6-6.5-6.1 0-3.4 2.6-5 3.6-8.4 1.7 1.3 2.4 3 2.4 4.5 1-.6 1.7-1.7 1.9-3.2 2.4 1.8 5.1 4.4 5.1 7.1 0 3.5-2.6 6.1-6.5 6.1z"/>',
    'link': '<path d="M10 14a4 4 0 005.7 0l3-3a4 4 0 00-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 00-5.7 0l-3 3a4 4 0 005.7 5.7l1-1"/>',
    'ext': '<path d="M14 4.5h5.5V10M19.5 4.5L11 13"/><path d="M18 14v4a2 2 0 01-2 2H6a2 2 0 01-2-2V8a2 2 0 012-2h4"/>',
    'spark': '<path d="M12 3.5l1.9 5 5.1 1.9-5.1 1.9-1.9 5-1.9-5L5 10.4l5.1-1.9z"/>',
    'more': '<circle cx="6" cy="12" r="1.4" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/><circle cx="18" cy="12" r="1.4" fill="currentColor" stroke="none"/>',
    'side': '<rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><path d="M9.5 4.5v15"/>',
    'retry': '<path d="M4.5 12a7.5 7.5 0 0113-5.1L19.5 9"/><path d="M19.5 4.5V9H15"/>',
    'home': '<path d="M4 11l8-6.5 8 6.5V19a1.5 1.5 0 01-1.5 1.5h-4v-5.5h-5v5.5h-4A1.5 1.5 0 014 19z"/>',
    'book': '<path d="M5 5.5a2 2 0 012-2h11.5v14H7a2 2 0 00-2 2z"/><path d="M5 19.5a2 2 0 002 2h11.5v-4"/>',
    'chevr': '<path d="M9.5 6l6 6-6 6"/>',
    'chevl': '<path d="M14.5 6l-6 6 6 6"/>',
    'chevd': '<path d="M6 9.5l6 6 6-6"/>',
    'play': '<path d="M8 5.5v13l10.5-6.5z" fill="currentColor"/>',
    'err': '<circle cx="12" cy="12" r="8.5"/><path d="M12 8v4.5"/><circle cx="12" cy="15.8" r=".9" fill="currentColor" stroke="none"/>',
    'shield': '<path d="M12 3.5l7 2.8v5.2c0 4.3-2.9 7.6-7 9-4.1-1.4-7-4.7-7-9V6.3z"/><path d="M9 12l2.2 2.2L15.5 10"/>',
    'heartbeat': '<path d="M3.5 12h4l2-4 3 8 2-4h6"/>',
    'swap': '<path d="M5 8.5h13M14.5 5l3.5 3.5-3.5 3.5"/><path d="M19 15.5H6M9.5 12L6 15.5 9.5 19"/>',
    'stop': '<rect x="6.5" y="6.5" width="11" height="11" rx="2.5" fill="currentColor" stroke="none"/>',
    'leaf': '<path d="M5 19c0-8 5-13 14-14-1 9-6 14-14 14z"/><path d="M5 19l7-7"/>',
    'bowl': '<path d="M3.5 11h17a8.5 8.5 0 01-17 0z"/><path d="M8 7.5c0-1.5 1-2 1-3.5M12 7.5c0-1.5 1-2 1-3.5M16 7.5c0-1.5 1-2 1-3.5"/>',
    'snow': '<path d="M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9"/>',
}


def ic(name, size=18, sw=2, style=''):
    return (f'<svg class="i" width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            f'stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round" style="{style}">{P[name]}</svg>')


_uid = [0]


def _id():
    _uid[0] += 1
    return f'g{_uid[0]}'


def tteok(size, rev=False):
    """버터 떡볶이 위에서 본 그림. rev=True면 어묵 없이 양배추가 들어간 고친 버전."""
    g = _id()
    cakes = [(62, 70, 28), (98, 62, -12), (70, 104, 64), (112, 98, 8), (88, 130, -30), (124, 128, 40), (56, 128, 100)]
    cake = ''.join(f'<g transform="rotate({a} {x+22} {y+7})"><rect x="{x}" y="{y}" width="44" height="14" rx="7" fill="#FBEAD2"/>'
                   f'<rect x="{x+3}" y="{y+2}" width="38" height="4" rx="2" fill="#FFF7EA" opacity=".8"/></g>' for x, y, a in cakes)
    if rev:
        extra = ('<path d="M120 70c10-6 22-2 24 8-8 4-18 4-24-8z" fill="#CFE3B3"/><path d="M58 96c6-9 18-10 24-2-6 7-17 8-24 2z" fill="#D8E9BF"/>'
                 '<path d="M96 140c8-7 20-5 22 4-8 5-17 5-22-4z" fill="#CFE3B3"/>')
    else:
        extra = ('<g transform="rotate(-18 128 76)"><rect x="112" y="66" width="34" height="22" rx="5" fill="#E9B97A"/><path d="M114 72h30M114 78h30M114 84h30" stroke="#D9A363" stroke-width="1.5"/></g>'
                 '<g transform="rotate(24 64 98)"><rect x="48" y="88" width="32" height="20" rx="5" fill="#E9B97A"/><path d="M50 94h28M50 100h28" stroke="#D9A363" stroke-width="1.5"/></g>')
    rings = ''.join(f'<circle cx="{x}" cy="{y}" r="5.5" fill="none" stroke="#6FA35A" stroke-width="3"/>' for x, y in [(84, 88), (132, 112), (100, 150), (66, 116), (118, 58)])
    sesame = ''.join(f'<ellipse cx="{x}" cy="{y}" rx="2.2" ry="1.2" fill="#FFF3D6" transform="rotate({a} {x} {y})"/>' for x, y, a in
                     [(92, 78, 20), (106, 118, -30), (78, 140, 50), (140, 96, 10), (70, 82, -40), (116, 84, 70), (96, 102, 0)])
    return f'''<svg width="{size}" height="{size}" viewBox="0 0 200 200" style="display:block;flex:none">
<defs><radialGradient id="{g}" cx=".42" cy=".38" r=".7"><stop offset="0" stop-color="#F5A061"/><stop offset=".55" stop-color="#DC5631"/><stop offset="1" stop-color="#A93A20"/></radialGradient>
<radialGradient id="{g}b" cx=".5" cy=".5" r=".5"><stop offset=".86" stop-color="#FFFDF8"/><stop offset=".93" stop-color="#EDE3D2"/><stop offset="1" stop-color="#E2D6C1"/></radialGradient></defs>
<circle cx="100" cy="104" r="96" fill="rgba(16,32,27,.12)"/><circle cx="100" cy="100" r="96" fill="url(#{g}b)"/>
<circle cx="100" cy="100" r="74" fill="url(#{g})"/>{cake}{extra}
<g transform="rotate(8 98 92)"><rect x="86" y="80" width="24" height="22" rx="4" fill="#F6D774"/><rect x="88" y="82" width="20" height="6" rx="3" fill="#FFF0A8"/></g>
{rings}{sesame}<ellipse cx="74" cy="66" rx="16" ry="7" fill="#fff" opacity=".18" transform="rotate(-30 74 66)"/></svg>'''


def mala(size):
    g = _id()
    bits = ''.join(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{c}" transform="rotate({a} {x} {y})"/>' for x, y, w, h, r, c, a in [
        (60, 70, 30, 16, 8, '#F2D6B3', 20), (110, 64, 26, 26, 6, '#E8C48E', -10), (80, 112, 34, 12, 6, '#FBEAD2', 40),
        (122, 110, 22, 22, 11, '#F4A38B', 0), (64, 120, 18, 30, 6, '#8DBB6A', -20), (100, 140, 30, 14, 7, '#F2D6B3', -30)])
    chili = ''.join(f'<path d="M{x} {y}q8 -6 16 0" stroke="#7E1A12" stroke-width="5" fill="none" stroke-linecap="round" transform="rotate({a} {x} {y})"/>' for x, y, a in [(90, 90, 10), (130, 90, -40), (70, 100, 60), (110, 126, 20)])
    return f'''<svg width="{size}" height="{size}" viewBox="0 0 200 200" style="display:block;flex:none">
<defs><radialGradient id="{g}" cx=".42" cy=".38" r=".7"><stop offset="0" stop-color="#D9643A"/><stop offset=".6" stop-color="#A8321C"/><stop offset="1" stop-color="#6E1E12"/></radialGradient></defs>
<circle cx="100" cy="104" r="96" fill="rgba(16,32,27,.12)"/><circle cx="100" cy="100" r="96" fill="#FFFDF8"/><circle cx="100" cy="100" r="88" fill="none" stroke="#E6DCCB" stroke-width="3"/>
<circle cx="100" cy="100" r="74" fill="url(#{g})"/>{bits}{chili}</svg>'''


BANNER = {
    'red': ('충돌', 'alert'), 'yel': ('확인 필요', 'q'), 'gry': ('검사 대상 없음', 'minus'), 'grn': ('찾지 못함', 'check'),
}


def banner(state, msg, sub='', action='', compact=False):
    k, icon = BANNER[state]
    s = f'<div class="s">{sub}</div>' if sub else ''
    a = f'<div class="bact">{action}</div>' if action else ''
    return (f'<div class="bn bn-{state}{" cp" if compact else ""}"><div class="bic">{ic(icon, 16 if compact else 18, 2.6)}</div>'
            f'<div style="flex:1;min-width:0"><div class="k">{k}</div><div class="m">{msg}</div>{s}{a}</div></div>')


def health(msg, compact=False):
    return (f'<div class="hn{" cp" if compact else ""}"><div class="hic">{ic("heartbeat", 15, 2.4)}</div>'
            f'<div><div class="k">건강 주의 · 의료 조언 아님</div><div class="m">{msg}</div></div></div>')


def tag(kind, text):
    icon = {'r': 'alert', 'y': 'q', 'f': 'swap', 'z': None}[kind]
    i = ic(icon, 12, 2.8) if icon else ''
    return f'<span class="tg2 t{kind}">{i}{text}</span>'


def lockchip(name, dark=False):
    return f'<span class="lk{" dk" if dark else ""}">{ic("lock", 12, 2.4)}{name}</span>'


def logo(size=28, dark=True):
    bg = 'var(--cream)' if dark else 'var(--forest)'
    return (f'<span style="width:{size}px;height:{size}px;border-radius:{size*.32}px;background:{bg};position:relative;display:inline-block;flex:none">'
            f'<i style="position:absolute;width:{size*.34}px;height:{size*.34}px;border-radius:50%;background:#F87820;left:{size*.33}px;top:{size*.33}px"></i></span>')


CSS = """
:root{--ink3:#646B66;--terra:#C2502F;--terraD:#A9411F}
.i{flex:none;display:block}
.lk{display:inline-flex;align-items:center;gap:5px;height:28px;padding:0 10px;border-radius:999px;background:#DCEBE3;color:var(--forest);font-size:12.5px;font-weight:800;white-space:nowrap}
.lk.dk{background:rgba(168,213,186,.16);color:var(--mint)}
.bn{display:flex;gap:12px;align-items:flex-start;padding:14px 16px;border-radius:16px;border:1px solid transparent}
.bn .bic{width:30px;height:30px;border-radius:10px;display:flex;align-items:center;justify-content:center;color:#fff;flex:none}
.bn .k{font-size:11.5px;font-weight:900;letter-spacing:.4px}
.bn .m{font-size:15px;font-weight:800;line-height:1.4;margin-top:2px;color:var(--ink)}
.bn .s{font-size:13px;color:var(--ink2);line-height:1.5;margin-top:4px}
.bn .bact{margin-top:10px}
.bn.cp{padding:10px 12px;gap:10px;border-radius:13px}.bn.cp .bic{width:24px;height:24px;border-radius:8px}.bn.cp .m{font-size:13.5px}
.bn-red{background:#FBEAE7;border-color:#F1CBC4}.bn-red .bic{background:#B83224}.bn-red .k{color:#B83224}
.bn-yel{background:#FCF1DA;border-color:#F0DDB2}.bn-yel .bic{background:#9A6410}.bn-yel .k{color:#8A5A0E}
.bn-gry{background:#EFEDE7;border-color:#DEDBD2}.bn-gry .bic{background:#6B716D}.bn-gry .k{color:#5A605C}
.bn-grn{background:#E4F1E8;border-color:#C7E0CF}.bn-grn .bic{background:#2F7D4F}.bn-grn .k{color:#276A43}
.hn{display:flex;gap:10px;align-items:flex-start;padding:12px 14px;border-radius:14px;background:var(--paper);border:1px solid var(--line);border-left:4px solid #C98A1A}
.hn .hic{color:#9A6410;margin-top:1px}
.hn .k{font-size:11.5px;font-weight:900;color:#8A5A0E;letter-spacing:.3px}
.hn .m{font-size:13.5px;line-height:1.5;margin-top:2px}
.hn.cp{padding:9px 12px}.hn.cp .m{font-size:13px}
.tg2{display:inline-flex;align-items:center;gap:4px;height:22px;padding:0 8px;border-radius:7px;font-size:11.5px;font-weight:800;white-space:nowrap;vertical-align:middle}
.tr{background:#FBEAE7;color:#B83224}.ty{background:#FCF1DA;color:#8A5A0E}.tf{background:#DCEBE3;color:var(--forest)}.tz{background:#EFEDE7;color:#5A605C}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;height:44px;padding:0 18px;border-radius:999px;font-size:14px;font-weight:800;white-space:nowrap}
.b-terra{background:var(--terra);color:#fff}.b-forest{background:var(--forest);color:var(--cream)}.b-soft{background:var(--cream2);color:var(--ink)}
.b-line{background:var(--paper);border:1px solid var(--line);color:var(--ink)}.b-ink{background:var(--ink);color:#fff}
.ghost{display:inline-flex;align-items:center;gap:6px;height:34px;padding:0 12px;border-radius:999px;font-size:13px;font-weight:700;color:var(--ink2);border:1px solid var(--line);background:var(--paper)}
.av{width:30px;height:30px;border-radius:10px;background:var(--forest);display:flex;align-items:center;justify-content:center;flex:none}
.me{background:var(--forest);color:var(--cream);margin-left:auto;padding:12px 16px;border-radius:20px 20px 6px 20px;font-size:15px;line-height:1.5;width:fit-content;max-width:72%}
.meta{font-size:11.5px;color:var(--ink3);text-align:right;margin-top:5px}
.ai{font-size:15px;line-height:1.65;color:var(--ink)}
.stp{display:flex;gap:12px;align-items:flex-start;padding:10px 0;position:relative}
.stp .d{width:26px;height:26px;border-radius:50%;flex:none;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:900;position:relative;z-index:1}
.stp .d.ok{background:var(--forest);color:#fff}.stp .d.on{background:var(--paper);border:2.5px solid var(--terra);color:var(--terra)}.stp .d.no{background:var(--paper);border:2px solid var(--line);color:var(--ink3)}
.stp .t{font-size:14.5px;font-weight:800}.stp .x{font-size:12.5px;color:var(--ink2);margin-top:3px;line-height:1.5}
.stp:not(:last-child):after{content:'';position:absolute;left:12px;top:36px;bottom:-10px;width:2px;background:var(--line)}
.stp.done:not(:last-child):after{background:var(--forest)}
.ring{width:26px;height:26px;border-radius:50%;border:2.5px solid #F3D3C8;border-top-color:var(--terra);position:absolute;inset:-2.5px}
.bar{height:6px;border-radius:3px;background:var(--cream2);overflow:hidden;margin-top:8px}.bar i{display:block;height:100%;background:var(--terra);border-radius:3px}
.sk{background:linear-gradient(90deg,#EDE6D8,#F6F1E7,#EDE6D8);border-radius:8px}
.ingr{display:flex;align-items:center;gap:10px;padding:11px 0;border-bottom:1px solid var(--line);font-size:14.5px}
.ingr .n{flex:1;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.ingr .q{color:var(--ink2);font-variant-numeric:tabular-nums}
.ingr.hit{background:linear-gradient(90deg,#FBEAE7,transparent 70%);margin:0 -10px;padding-left:10px;padding-right:10px;border-radius:8px}
.gh{font-size:12px;font-weight:900;color:var(--ink3);letter-spacing:.4px;margin:16px 0 2px}
.tabs{display:flex;gap:22px;border-bottom:1px solid var(--line)}
.tabs div{padding:12px 0 11px;font-size:14.5px;font-weight:800;color:var(--ink3);display:flex;gap:6px;align-items:center}
.tabs div span{font-size:12px;background:var(--cream2);color:var(--ink2);padding:1px 7px;border-radius:999px}
.tabs .on{color:var(--ink);box-shadow:inset 0 -3px 0 var(--terra)}
.tabs .on span{background:var(--terra);color:#fff}
.step{display:flex;gap:14px;padding:14px 0;border-bottom:1px solid var(--line);font-size:14.5px;line-height:1.6}
.step b{width:26px;height:26px;border-radius:9px;background:var(--forest);color:var(--cream);font-size:13px;display:flex;align-items:center;justify-content:center;flex:none;margin-top:1px}
.step .tm{display:inline-flex;align-items:center;gap:4px;font-size:12px;font-weight:800;color:var(--terraD);background:#FBE7DF;padding:2px 8px;border-radius:999px;margin-top:6px}
.toast{display:flex;align-items:center;gap:12px;background:var(--ink);color:#fff;border-radius:16px;padding:12px 14px 12px 16px;font-size:14px;font-weight:700;box-shadow:0 16px 40px rgba(16,32,27,.3)}
.toast a{color:var(--butter);font-weight:800}
.kbd{font-size:11px;font-weight:800;border:1px solid rgba(255,255,255,.25);border-radius:6px;padding:2px 6px;opacity:.7}
"""
