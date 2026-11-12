# 방향 5. 작업대: 왼쪽은 대화, 오른쪽은 표로 된 레시피 문서. 재료마다 확인 상태를 칸으로 둔다.
from v5_data import *

KEY, LABEL = '5-desk', '5. 작업대'
CONCEPT = '대화와 레시피 문서를 나란히. 재료 표에 확인 칸을 두어 무엇을 볼지 한눈에'
FONTS = "@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&display=swap');"

CSS = '''
.dk{--bg:#FAFAF7;--ink:#17201C;--ink2:#545B57;--ink3:#7B817D;--grid:#E2E2DC;--acc:#0B3B32;--warn:#8A5300;--warnBg:#FBF1DC;
  background:var(--bg);color:var(--ink);font-family:'IBM Plex Sans KR',Pretendard,sans-serif;height:100%;position:relative;font-size:14.5px}
.w5{display:grid;grid-template-columns:56px 440px 1fr;height:900px}
.rail{border-right:1px solid var(--grid);display:flex;flex-direction:column;align-items:center;padding-top:18px;gap:22px;font-size:11.5px;color:var(--ink3)}
.rail .wm{width:30px;height:30px;border-radius:7px;background:var(--acc);color:#fff;font-weight:700;display:flex;align-items:center;justify-content:center;font-size:15px}
.rail .on{color:var(--acc);font-weight:700}
.cv{border-right:1px solid var(--grid);position:relative;background:#fff}
.cv .hd{height:52px;border-bottom:1px solid var(--grid);display:flex;align-items:center;justify-content:space-between;padding:0 20px;font-weight:600}
.cv .hd span{font-weight:400;color:var(--ink3);font-size:13px}
.cv .ms{padding:18px 20px;display:flex;flex-direction:column;gap:14px}
.cv .me{align-self:flex-end;background:#EEF1EE;padding:10px 13px;border-radius:10px;max-width:330px;line-height:1.55}
.cv .bt{line-height:1.7}
.cv .bt .lk{color:var(--acc);font-weight:600}
.cv .chg{border-left:2px solid var(--grid);padding-left:12px;font-size:13.5px;color:var(--ink2);line-height:1.8}
.cv .chg s{color:var(--ink3)}
.cv .cmp{position:absolute;left:16px;right:16px;bottom:16px;border:1px solid #CFCFC8;border-radius:8px;padding:12px 14px 10px;color:var(--ink3);background:#fff}
.cv .cmp div{display:flex;justify-content:space-between;margin-top:18px;font-size:12.5px}
.cv .cmp b{color:#fff;background:var(--acc);padding:5px 12px;border-radius:6px;font-weight:600}
.dc{overflow:hidden;position:relative}
.dc .bar{height:52px;border-bottom:1px solid var(--grid);display:flex;align-items:center;gap:22px;padding:0 28px;font-size:13.5px}
.dc .bar .tb{color:var(--ink3);height:52px;display:flex;align-items:center}
.dc .bar .tb.on{color:var(--ink);font-weight:600;box-shadow:inset 0 -2px var(--ink)}
.dc .bar .ac{margin-left:auto;display:flex;gap:8px}
.dc .bar .ac span{border:1px solid #CFCFC8;border-radius:6px;padding:5px 12px;background:#fff;font-weight:500}
.dc .bar .ac span.p{background:var(--acc);border-color:var(--acc);color:#fff}
.pg{padding:26px 36px}
.pg h1{font-size:28px;font-weight:700;letter-spacing:-.5px}
.pg .mt{display:flex;gap:26px;margin-top:10px;font-size:13.5px;color:var(--ink2)}
.pg .mt b{color:var(--ink);font-weight:600;margin-left:6px}
.sum{margin-top:18px;display:grid;grid-template-columns:1fr 1fr;border:1px solid var(--grid);background:#fff}
.sum>div{padding:12px 16px;line-height:1.6}
.sum>div+div{border-left:1px solid var(--grid)}
.sum h5{font-size:12.5px;color:var(--ink3);font-weight:600;margin-bottom:4px}
.sum .w{color:var(--warn);font-weight:700}
table{width:100%;border-collapse:collapse;margin-top:20px;background:#fff;border:1px solid var(--grid)}
th{font-size:12.5px;color:var(--ink3);font-weight:600;text-align:left;padding:8px 12px;border-bottom:1px solid var(--grid);background:#F4F4F0}
td{padding:8px 12px;border-bottom:1px solid var(--grid);vertical-align:top}
td.q{font-variant-numeric:tabular-nums;width:90px}
td.s{width:110px}
td .ck{display:inline-block;background:var(--warnBg);color:var(--warn);font-weight:600;font-size:12.5px;padding:1px 7px;border-radius:4px}
td.n{color:var(--ink2);font-size:13.5px}
tr.chg td:first-child{box-shadow:inset 2px 0 var(--warn)}
.stp{margin-top:22px;display:grid;grid-template-columns:1fr 1fr;gap:0 28px}
.stp h4,.src h4{font-size:12.5px;color:var(--ink3);font-weight:600;margin-bottom:6px}
.stp ol{margin-left:18px;line-height:1.7}
.foot{font-size:12.5px;color:var(--ink3);margin-top:16px;line-height:1.6}
/* app */
.dk.a{height:844px;background:#fff}
.atb{display:flex;align-items:center;justify-content:space-between;padding:4px 16px 0;font-weight:600}
.atb span{color:var(--acc)}
.seg{display:flex;border-bottom:1px solid var(--grid);margin-top:10px;padding:0 16px;gap:22px;font-size:14.5px}
.seg span{padding:10px 0;color:var(--ink3)}
.seg .on{color:var(--ink);font-weight:600;box-shadow:inset 0 -2px var(--ink)}
.seg em{font-style:normal;color:var(--warn);font-size:12px;margin-left:4px}
.ab{padding:14px 16px 24px;height:598px;overflow-y:auto}
.ab h1{font-size:23px}
.ab .sum{grid-template-columns:1fr}
.ab .sum>div+div{border-left:0;border-top:1px solid var(--grid)}
.ab table{font-size:13.5px}
.ab td.s{width:100px} .ab td .ck{white-space:nowrap}
.ab td.q{width:70px}
.acmp{position:absolute;left:12px;right:12px;bottom:30px;border:1px solid #CFCFC8;border-radius:10px;height:48px;display:flex;align-items:center;justify-content:space-between;padding:0 6px 0 14px;color:var(--ink3);background:#fff}
.acmp b{background:var(--acc);color:#fff;padding:7px 12px;border-radius:7px;font-size:13.5px}
.hm{padding:20px 16px}
.hm h2{font-size:24px;font-weight:700;letter-spacing:-.4px}
.hm .in{margin-top:14px;border:1px solid #CFCFC8;border-radius:10px;padding:14px;min-height:110px;color:var(--ink3);position:relative;background:#fff}
.hm .in b{position:absolute;right:8px;bottom:8px;background:var(--acc);color:#fff;padding:6px 12px;border-radius:6px;font-size:13px}
.hm .bs{margin-top:16px;border:1px solid var(--grid)}
.hm .bs div{display:flex;justify-content:space-between;padding:10px 12px;font-size:14px}
.hm .bs div+div{border-top:1px solid var(--grid)}
.hm .bs span{color:var(--ink3)}
.hm h6{font-size:12.5px;color:var(--ink3);font-weight:600;margin-top:26px}
.hm .rc{display:flex;justify-content:space-between;padding:11px 0;border-bottom:1px solid var(--grid)}
.hm .rc small{color:var(--ink3)}
.nv{position:absolute;bottom:0;left:0;right:0;height:84px;border-top:1px solid var(--grid);display:flex;justify-content:space-around;padding-top:14px;font-size:12.5px;color:var(--ink3);background:#fff}
.nv .on{color:var(--acc);font-weight:700}
.ls{padding:16px}
.ls h2{font-size:24px;font-weight:700}
.ls table{margin-top:14px;font-size:13.5px}
.ls td b{font-weight:600;display:block;font-size:14.5px}
.ls td small{color:var(--ink3)}
.ls .g{color:var(--ink3)}
'''


def table(items, app=False):
    rows = ''
    for n, q, m in items:
        chk = '<span class="ck">원재료 확인</span>' if '원재료 확인' in m else ''
        memo = m.replace(', 원재료 확인', '').replace('원재료 확인', '')
        cls = 'chg' if memo else ''
        if app:
            rows += f'<tr class="{cls}"><td>{n}{f"<br><span class=n>{memo}</span>" if memo else ""}</td><td class="q">{q}</td><td class="s">{chk}</td></tr>'
        else:
            rows += f'<tr class="{cls}"><td>{n}</td><td class="q">{q}</td><td class="n">{memo}</td><td class="s">{chk}</td></tr>'
    head = '<tr><th>재료</th><th>양</th><th>확인</th></tr>' if app else '<tr><th>재료</th><th>양</th><th>메모</th><th>알레르기 확인</th></tr>'
    return f'<table>{head}{rows}</table>'


def summary():
    ck = '<br>'.join(f'<b>{a}</b> <span class="w">{STATUS[s]}</span>, {"귀리음료" if a == "우유" else "어묵"} 원재료' for a, s, t in CHECKS)
    return f'<div class="sum"><div><h5>알레르기 확인 ({", ".join(ALLERGIES)})</h5>{ck}</div><div><h5>건강 참고 ({HEALTH[0]})</h5>{HEALTH[1]}</div></div>'


def web():
    chg = '<br>'.join(f'<s>{a}</s> {b}' for a, b in CHANGES)
    steps = ''.join(f'<li>{s}</li>' for s in STEPS)
    frame = f'''<div class="dk"><div class="w5">
<nav class="rail"><span class="wm">S</span><span class="on">대화</span><span>레시피</span><span>식단</span><span>내 정보</span></nav>
<section class="cv"><div class="hd">{CONVOS[0][0]}<span>오늘</span></div><div class="ms">
<div class="me">{Q}</div><div class="bt">{INTRO} <span class="lk">오른쪽 문서</span>에 정리했어요.</div>
<div class="chg">{chg}</div>
<div class="bt">어묵과 귀리음료는 제품마다 원재료가 달라 확인 칸에 표시해 뒀어요.</div></div>
<div class="cmp">바꾸고 싶은 점을 적어 주세요<div><span>{', '.join(ALLERGIES)} 알레르기 기준</span><b>보내기</b></div></div></section>
<section class="dc"><div class="bar"><span class="tb on">레시피</span><span class="tb">바꾼 점 {len(CHANGES)}</span><span class="tb">참고한 글 {len(SOURCES)}</span>
<div class="ac"><span>식단에 기록</span><span class="p">저장</span></div></div>
<div class="pg"><h1>{TITLE}</h1><div class="mt"><span>분량<b>{SERVES}</b></span><span>조리<b>{TIME}</b></span><span>요청<b>{NOTE}</b></span></div>
{summary()}{table(INGR)}
<div class="stp"><div><h4>만드는 법</h4><ol>{''.join(f'<li>{s}</li>' for s in STEPS[:3])}</ol></div><div><h4>&nbsp;</h4><ol start="4">{''.join(f'<li>{s}</li>' for s in STEPS[3:])}</ol><p class="foot">{TIP}</p></div></div>
<p class="foot">{DISCLAIMER}</p></div></section></div></div>'''
    return web_board(KEY, LABEL, CONCEPT, CSS, frame, FONTS)


def app():
    sb = '<div class="sb"><span>9:41</span><span>●●● 100%</span></div>'
    nv = lambda on: '<nav class="nv">' + ''.join(f'<span class="{"on" if n == on else ""}">{n}</span>' for n in ['대화', '레시피', '식단', '내 정보']) + '</nav>'
    rc = ''.join(f'<div class="rc">{t}<small>{d}</small></div>' for t, d in CONVOS[:4])
    home = f'''<div class="dk a">{sb}<div class="hm"><h2>새 대화</h2><div class="in">만들고 싶은 요리와 조건을 적어 주세요<b>보내기</b></div>
<div class="bs"><div><span>알레르기</span>{', '.join(ALLERGIES)}</div><div><span>건강 정보</span>{HEALTH_TAGS[0]}</div></div>
<h6>최근 대화</h6>{rc}</div>{nv('대화')}</div>'''
    res = f'''<div class="dk a">{sb}<div class="atb"><span>‹</span>{CONVOS[0][0]}<span>저장</span></div>
<div class="seg"><span>대화</span><span class="on">레시피<em>확인 2</em></span><span>바꾼 점</span></div>
<div class="ab"><h1>{TITLE}</h1><div class="mt" style="display:flex;gap:14px;font-size:13px;color:#545B57;margin-top:6px"><span>{SERVES}</span><span>{TIME}</span><span>{NOTE}</span></div>
{summary()}{table(INGR, True)}<div class="stp" style="grid-template-columns:1fr"><div><h4>만드는 법</h4><ol>{''.join(f'<li>{s}</li>' for s in STEPS)}</ol></div></div><p class="foot">{DISCLAIMER}</p></div>
<div class="acmp"><span>바꾸고 싶은 점</span><b>보내기</b></div></div>'''
    trs = ''.join(f'<tr><td><b>{t}</b><small>{s}, {tm}</small></td><td>{f"<span class=ck>{x}</span>" if k == "check" else f"<span class=g>{x}</span>"}</td></tr>' for t, s, tm, x, k, d in SAVED)
    lst = f'''<div class="dk a">{sb}<div class="ls"><h2>레시피</h2><table><tr><th>이름</th><th>알레르기 확인</th></tr>{trs}</table></div>{nv('레시피')}</div>'''
    return app_board(KEY, LABEL, CONCEPT, CSS, [home, res, res.replace('class="ab"', 'class="ab" data-scroll="end"', 1), lst], FONTS)


if __name__ == '__main__':
    print(web()); print(app())
