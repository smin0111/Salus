# 방향 2. 식품 표시 라벨: 영양성분표처럼 검정 굵은 선과 표로 정보를 나눈다. 색은 형광펜 노랑(확인 필요) 하나.
from v5_data import *

KEY, LABEL = '2-label', '2. 식품 표시 라벨'
CONCEPT = '포장 뒷면 표시처럼 굵은 선과 표로. 확인할 곳만 형광펜으로'
FONTS = "@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@500;600&display=swap');"

CSS = '''
.lb{--ink:#111;--ink2:#4A4A4A;--ink3:#767676;--hi:#FFE45C;--red:#C8102E;background:#fff;color:var(--ink);font-family:Pretendard,sans-serif;height:100%;position:relative}
.mono{font-family:'IBM Plex Mono',monospace}
.mk{background:linear-gradient(transparent 12%,var(--hi) 12% 88%,transparent 88%);padding:0 3px;font-weight:800}
/* web */
.hd{height:64px;border-bottom:3px solid var(--ink);display:flex;align-items:center;padding:0 32px;gap:34px;font-size:15px}
.hd .wm{font-size:22px;font-weight:900;letter-spacing:-.6px}
.hd .nv{display:flex;gap:26px;color:var(--ink2);align-self:stretch} .hd .nv span{display:flex;align-items:center} .hd .nv .on{color:var(--ink);font-weight:800;box-shadow:inset 0 -4px var(--ink)}
.hd .me{margin-left:auto;font-size:13.5px;color:var(--ink2)} .hd .me b{color:var(--ink)}
.bodyw{display:grid;grid-template-columns:1fr 600px;height:833px}
.cv{padding:36px 56px 0 56px;position:relative;border-right:1px solid #DADADA}
.cv .crumb{font-size:13px;color:var(--ink3)} .cv .crumb u{color:var(--ink)}
.cv .u{margin:26px 0 0 auto;width:fit-content;max-width:440px;background:var(--ink);color:#fff;padding:12px 16px;font-size:15.5px;line-height:1.55;border-radius:2px}
.cv .who{font-size:12.5px;color:var(--ink3);margin-top:30px;font-weight:700}
.cv .a{font-size:16px;line-height:1.75;margin-top:6px;max-width:600px}
.cv .a p+p{margin-top:12px}
.cv .ref{margin-top:18px;font-size:14px;color:var(--ink2)}
.cv .ref b{color:var(--ink)}
.cv .cmp{position:absolute;left:56px;right:56px;bottom:28px;border:2px solid var(--ink);height:56px;display:flex;align-items:center;justify-content:space-between;padding:0 8px 0 18px;font-size:15.5px;color:var(--ink3)}
.cv .cmp b{background:var(--ink);color:#fff;font-size:14.5px;height:40px;display:flex;align-items:center;padding:0 18px}
.pn{padding:26px 34px;overflow:hidden;background:#F4F4F2}
.lab{background:#fff;border:2px solid var(--ink);padding:14px 18px 16px}
.lab h1{font-size:34px;font-weight:900;letter-spacing:-1.2px;line-height:1.1}
.lab .bas{display:flex;justify-content:space-between;font-size:14px;margin-top:8px;padding-bottom:8px}
.r10{border-top:10px solid var(--ink)} .r5{border-top:5px solid var(--ink)} .r1{border-top:1px solid var(--ink)}
.lab h4{font-size:14.5px;font-weight:900;padding:7px 0 5px;display:flex;justify-content:space-between}
.lab h4 span{font-weight:500;font-size:13px;color:var(--ink2)}
.row{display:grid;grid-template-columns:44px 1fr auto;gap:10px;padding:7px 0;border-top:1px solid var(--ink);font-size:13.5px;line-height:1.55}
.row b{font-weight:800}
.row .st{font-size:13px;white-space:nowrap}
.hlth{font-size:13.5px;line-height:1.6;padding:7px 0}
.tbl{width:100%;border-collapse:collapse;font-size:13.5px}
.tbl td{border-top:1px solid #BDBDBD;padding:5px 0;vertical-align:top}
.tbl tr:first-child td{border-top:1px solid var(--ink)}
.tbl td.q{text-align:right;font-family:'IBM Plex Mono',monospace;font-size:13px;width:78px}
.tbl td small{display:block;font-size:12px;color:var(--ink2)}
.stp{counter-reset:s;font-size:13.5px;line-height:1.6}
.stp li{list-style:none;display:grid;grid-template-columns:24px 1fr;padding:5px 0;border-top:1px solid #BDBDBD}
.stp li:first-child{border-top:1px solid var(--ink)}
.stp li b{font-family:'IBM Plex Mono',monospace;font-weight:600}
.pn .act{display:flex;gap:10px;margin-top:14px}
.pn .act span{flex:1;height:46px;border:2px solid var(--ink);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:15px;background:#fff}
.pn .act span.p{background:var(--ink);color:#fff}
/* app */
.lb.a{height:844px}
.atop{display:flex;justify-content:space-between;align-items:center;padding:6px 20px 12px;border-bottom:3px solid var(--ink)}
.atop .wm{font-size:21px;font-weight:900;letter-spacing:-.5px}
.atop a{font-size:14px;font-weight:700}
.tabs{position:absolute;bottom:0;left:0;right:0;height:84px;border-top:3px solid var(--ink);display:grid;grid-template-columns:repeat(4,1fr);text-align:center;padding-top:15px;font-size:13.5px;color:var(--ink3);background:#fff}
.tabs .on{color:var(--ink);font-weight:900}
.hm{padding:26px 20px}
.hm .lbl{font-size:13px;font-weight:800}
.hm .in{margin-top:8px;border:2px solid var(--ink);min-height:120px;padding:14px;font-size:16.5px;color:var(--ink3);position:relative}
.hm .in b{position:absolute;right:8px;bottom:8px;background:var(--ink);color:#fff;font-size:14px;padding:9px 14px}
.hm .bs{margin-top:20px;border-top:5px solid var(--ink);padding-top:8px;font-size:13.5px}
.hm .bs div{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #BDBDBD}
.hm .bs div span{color:var(--ink2)}
.hm .bs a{display:block;margin-top:8px;font-weight:700;text-decoration:underline;text-underline-offset:3px}
.hm h6{margin-top:34px;font-size:13px;font-weight:800;border-bottom:1px solid var(--ink);padding-bottom:6px}
.hm .rc{display:flex;justify-content:space-between;padding:11px 0;border-bottom:1px solid #DADADA;font-size:15px}
.hm .rc small{color:var(--ink3);font-size:12.5px}
.rbar{display:flex;justify-content:space-between;padding:4px 16px 10px;font-size:14.5px;font-weight:700;border-bottom:3px solid var(--ink)} .rbar span:last-child{display:flex;gap:16px}
.rs{padding:14px 16px 110px;height:752px;overflow-y:auto;background:#F4F4F2}
.rs .u{margin-left:auto;width:fit-content;max-width:270px;background:var(--ink);color:#fff;padding:10px 13px;font-size:14px;line-height:1.5}
.rs .a{font-size:14.5px;line-height:1.65;margin:14px 0}
.rs .lab{padding:12px 14px}
.rs .lab h1{font-size:26px}
.acmp{position:absolute;left:0;right:0;bottom:0;background:#fff;border-top:3px solid var(--ink);padding:12px 16px 34px;display:flex;gap:8px}
.acmp span{flex:1;border:2px solid var(--ink);height:46px;display:flex;align-items:center;padding:0 12px;color:var(--ink3);font-size:14.5px}
.acmp b{background:var(--ink);color:#fff;width:66px;display:flex;align-items:center;justify-content:center;font-size:14px}
.ls{padding:18px 20px}
.ls h2{font-size:28px;font-weight:900;letter-spacing:-.8px}
.ls .fl{display:flex;gap:8px;margin-top:12px}
.ls .fl span{border:2px solid var(--ink);padding:6px 12px;font-size:13.5px;font-weight:700}
.ls .fl span.on{background:var(--ink);color:#fff}
.ls table{width:100%;border-collapse:collapse;margin-top:16px;font-size:14px}
.ls th{font-size:12px;text-align:left;font-weight:800;border-bottom:5px solid var(--ink);padding:6px 0}
.ls td{border-bottom:1px solid #BDBDBD;padding:12px 0;vertical-align:top}
.ls td b{display:block;font-size:15.5px;font-weight:800}
.ls td small{color:var(--ink2);font-size:12.5px}
.ls td.s{text-align:right;font-size:13px;white-space:nowrap}
.ls .gr{color:var(--ink3)}
'''


def st(s):
    return f'<span class="mk">{STATUS[s]}</span>' if s == 'check' else STATUS[s]


def label(full=True):
    rows = ''.join(f'<div class="row"><b>{a}</b><span>{t}</span><span class="st">{st(s)}</span></div>' for a, s, t in CHECKS)
    ing = INGR if full else INGR[:5]
    trs = ''.join(f'<tr><td>{n}{f"<small>{m}</small>" if m else ""}</td><td class="q">{q}</td></tr>' for n, q, m in ing)
    steps = ''.join(f'<li><b>{i}</b><span>{s}</span></li>' for i, s in enumerate(STEPS, 1)) if full else ''
    return f'''<div class="lab"><h1>{TITLE}</h1>
<div class="bas"><span>{SERVES} 기준, {NOTE}</span><span>조리 {TIME}</span></div>
<div class="r10"></div><h4>알레르기 확인<span>등록: {', '.join(ALLERGIES)}</span></h4>{rows}
<div class="r5"></div><h4>건강 참고<span>{HEALTH[0]}</span></h4><p class="hlth">{HEALTH[1]}</p>
<div class="r5"></div><h4>재료<span>{SERVES}</span></h4><table class="tbl">{trs}</table>
{f'<div class="r5" style="margin-top:6px"></div><h4>만드는 법</h4><ol class="stp">{steps}</ol>' if full else ''}</div>'''


def web():
    changes = ', '.join(f'{a} {b}' for a, b in CHANGES)
    frame = f'''<div class="lb"><header class="hd"><span class="wm">Salus</span>
<nav class="nv"><span class="on">대화</span><span>식단</span><span>레시피</span></nav>
<span class="me"><b>{USER}</b> · 알레르기 {', '.join(ALLERGIES)} · {HEALTH_TAGS[0]}</span></header>
<div class="bodyw"><section class="cv"><div class="crumb"><u>지난 대화</u> / {CONVOS[0][0]}</div>
<div class="u">{Q}</div><div class="who">Salus</div>
<div class="a"><p>{INTRO}</p><p>어묵과 귀리음료는 제품마다 원재료가 달라서 <span class="mk">확인 필요</span>로 표시했어요. 오른쪽 표시면에서 확인할 내용을 볼 수 있어요.</p></div>
<p class="ref"><b>바꾼 점</b> {changes}<br><b>참고한 글</b> {SOURCES[0][2]}, {SOURCES[1][2]}</p>
<div class="cmp"><span>바꾸고 싶은 점을 적어 주세요</span><b>보내기</b></div></section>
<aside class="pn">{label()}<p style="font-size:12px;color:#767676;margin-top:10px;line-height:1.5">{DISCLAIMER}</p>
<div class="act"><span class="p">저장</span><span>식단에 기록</span></div></aside></div></div>'''
    return web_board(KEY, LABEL, CONCEPT, CSS, frame, FONTS)


def app():
    sb = '<div class="sb"><span>9:41</span><span>●●● 100%</span></div>'
    recent = ''.join(f'<div class="rc">{t}<small>{d}</small></div>' for t, d in CONVOS[:4])
    home = f'''<div class="lb a">{sb}<div class="atop"><span class="wm">Salus</span><a>지난 대화</a></div>
<div class="hm"><div class="lbl">무엇을 만들까요?</div><div class="in">요리 이름이나 재료를 적어 주세요<b>보내기</b></div>
<div class="bs"><div><span>알레르기</span><b>{', '.join(ALLERGIES)}</b></div><div><span>건강 정보</span><b>{HEALTH_TAGS[0]}</b></div><a>확인 기준 바꾸기</a></div>
<h6>최근 대화</h6>{recent}</div>
<nav class="tabs"><span class="on">대화</span><span>식단</span><span>레시피</span><span>내 정보</span></nav></div>'''
    res = f'''<div class="lb a">{sb}<div class="rbar"><span>← 대화</span><span><u>저장</u><u>식단에 기록</u></span></div><div class="rs"><div class="u">{Q}</div><p class="a">{INTRO}</p>{label()}<p style="font-size:12px;color:#767676;margin-top:8px">{DISCLAIMER}</p></div>
<div class="acmp"><span>바꾸고 싶은 점</span><b>보내기</b></div></div>'''
    trs = ''.join(f'<tr><td><b>{t}</b><small>{s}, {tm}, {d}</small></td><td class="s {"gr" if k=="unknown" else ""}">{f"<span class=mk>{x}</span>" if k=="check" else x}</td></tr>'
                  for t, s, tm, x, k, d in SAVED)
    lst = f'''<div class="lb a">{sb}<div class="ls"><h2>저장한 레시피</h2>
<div class="fl"><span class="on">전체 6</span><span>확인 필요 있음 3</span></div>
<table><tr><th>레시피</th><th style="text-align:right">알레르기 확인</th></tr>{trs}</table></div>
<nav class="tabs"><span>대화</span><span>식단</span><span class="on">레시피</span><span>내 정보</span></nav></div>'''
    return app_board(KEY, LABEL, CONCEPT, CSS, [home, res, res.replace('class="rs"', 'class="rs" data-scroll="end"', 1), lst], FONTS)


if __name__ == '__main__':
    print(web()); print(app())
