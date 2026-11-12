# 방향 3. 메모장: 시스템 앱처럼 작고 촘촘한 글자, 회색 바탕 목록, 꾸밈 없음. 강조색은 숲색 하나.
from v5_data import *

KEY, LABEL = '3-notes', '3. 메모장'
CONCEPT = '메모 앱처럼 조용하고 촘촘하게. 기능은 목록과 문서 두 가지뿐'

CSS = '''
.nt{--bg:#fff;--side:#F3F3F1;--ink:#1F2421;--ink2:#5E625F;--ink3:#8A8D8A;--line:#E6E6E3;--acc:#0B3B32;--warn:#9A5800;
  background:var(--bg);color:var(--ink);font-family:Pretendard,sans-serif;height:100%;position:relative;font-size:14.5px}
.w3{display:grid;grid-template-columns:200px 300px 1fr;height:900px}
.s1{background:var(--side);padding:22px 14px;border-right:1px solid var(--line)}
.s1 .wm{font-size:17px;font-weight:800;padding:0 8px;color:var(--acc)}
.s1 ul{list-style:none;margin-top:22px}
.s1 li{padding:7px 8px;border-radius:6px;display:flex;justify-content:space-between;color:var(--ink2)}
.s1 li.on{background:#E3E4E0;color:var(--ink);font-weight:700}
.s1 li small{color:var(--ink3)}
.s1 .pf{position:absolute;bottom:22px;left:22px;width:160px;font-size:12.5px;color:var(--ink2);line-height:1.6}
.s2{border-right:1px solid var(--line);padding:16px 0}
.s2 .sr{margin:0 14px;height:32px;border-radius:7px;background:var(--side);font-size:13px;color:var(--ink3);display:flex;align-items:center;padding:0 10px}
.s2 h6{font-size:12px;color:var(--ink3);font-weight:600;padding:16px 18px 6px}
.s2 .it{padding:10px 18px;border-bottom:1px solid var(--line)}
.s2 .it.on{background:#EEF2EF}
.s2 .it b{display:block;font-size:14.5px}
.s2 .it p{font-size:12.5px;color:var(--ink3);margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.s3{position:relative;overflow:hidden}
.s3 .tb{height:46px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:flex-end;gap:20px;padding:0 24px;font-size:13.5px;color:var(--acc);font-weight:600}
.s3 .tb .l{margin-right:auto;color:var(--ink3);font-weight:400}
.doc{max-width:720px;margin:0 auto;padding:22px 0 140px}
.m{font-size:12px;color:var(--ink3);margin-top:18px}
.u{margin-top:4px;color:var(--ink2)}
.a{margin-top:4px;line-height:1.7}
.doc h1{font-size:24px;font-weight:800;margin-top:22px;letter-spacing:-.4px}
.doc .mt{color:var(--ink3);font-size:13px;margin-top:4px}
.doc h5{font-size:12.5px;color:var(--ink3);font-weight:700;margin:20px 0 6px}
.cl{border-top:1px solid var(--line)}
.cl div{display:grid;grid-template-columns:44px 1fr 72px;gap:10px;padding:8px 0;border-bottom:1px solid var(--line);line-height:1.6}
.cl em{font-style:normal;color:var(--warn);font-weight:700;text-align:right;font-size:13px}
.ig{columns:2;column-gap:36px}
.ig div{display:flex;justify-content:space-between;padding:4px 0;break-inside:avoid}
.ig div span:last-child{color:var(--ink2)}
.ig div small{color:var(--warn);font-size:12px;margin-left:6px}
.sp li{margin-left:20px;padding:3px 0;line-height:1.65}
.sm{font-size:12.5px;color:var(--ink3);line-height:1.7;margin-top:20px;border-top:1px solid var(--line);padding-top:10px}
.cmp{position:absolute;left:24px;right:24px;bottom:20px;height:42px;border:1px solid #D6D6D2;border-radius:8px;background:#fff;display:flex;align-items:center;justify-content:space-between;padding:0 12px;color:var(--ink3);font-size:14px;box-shadow:0 0 0 6px #fff}
.cmp b{color:var(--acc);font-size:13.5px}
/* app */
.nt.a{height:844px;font-size:15px}
.nt.a.g{background:var(--side)}
.ah{padding:6px 18px 0}
.ah .r{display:flex;justify-content:space-between;color:var(--acc);font-size:15px;font-weight:600}
.ah h2{font-size:30px;font-weight:800;letter-spacing:-.6px;margin-top:10px}
.ah .sr{margin-top:10px;height:36px;border-radius:9px;background:#E6E6E2;color:var(--ink3);display:flex;align-items:center;padding:0 12px;font-size:14.5px}
.grp{margin:18px 16px 0;background:#fff;border-radius:10px}
.grp h6{font-size:12.5px;color:var(--ink3);font-weight:600;margin:18px 18px 6px}
.grp .it{padding:11px 14px;border-bottom:1px solid var(--line)}
.grp .it:last-child{border-bottom:0}
.grp .it b{display:block;font-size:15.5px}
.grp .it p{font-size:13px;color:var(--ink3);margin-top:3px}
.grp .it p em{font-style:normal;color:var(--warn);font-weight:600}
.lbl{font-size:12.5px;color:var(--ink3);font-weight:600;margin:20px 30px 0}
.abar{position:absolute;bottom:0;left:0;right:0;height:86px;border-top:1px solid var(--line);background:rgba(255,255,255,.96);display:flex;justify-content:space-around;padding-top:12px;font-size:12.5px;color:var(--ink3)}
.abar .on{color:var(--acc);font-weight:700}
.fab{position:absolute;right:20px;bottom:104px;height:46px;padding:0 18px;border-radius:23px;background:var(--acc);color:#fff;font-weight:700;display:flex;align-items:center;font-size:15px}
.ad{padding:0 18px 24px;height:706px;overflow-y:auto}
.ad .top{display:flex;justify-content:space-between;color:var(--acc);font-weight:600;padding:6px 0 4px}
.ad h1{font-size:23px;margin-top:16px}
.ad .ig{columns:1}
.acmp{position:absolute;left:12px;right:12px;bottom:30px;height:44px;border:1px solid #D6D6D2;border-radius:22px;display:flex;align-items:center;justify-content:space-between;padding:0 16px;color:var(--ink3);font-size:14.5px;background:#fff;box-shadow:0 0 0 14px #fff}
.acmp b{color:var(--acc)}
'''


def checklist():
    return '<div class="cl">' + ''.join(f'<div><b>{a}</b><span>{t}</span><em>{STATUS[s]}</em></div>' for a, s, t in CHECKS) + '</div>'


def ingr(items):
    return '<div class="ig">' + ''.join(f'<div><span>{n}{f"<small>{m}</small>" if m else ""}</span><span>{q}</span></div>' for n, q, m in items) + '</div>'


def web():
    folders = [('대화', 6, True), ('저장한 레시피', 6, False), ('식단 기록', 23, False)]
    s1 = ''.join(f'<li class="{"on" if on else ""}">{n}<small>{c}</small></li>' for n, c, on in folders)
    previews = ['귀리음료 로제떡볶이, 확인 필요 2', '땅콩 뺀 마라샹궈, 확인 필요 3', '참깨 대신 들깨로 바꾼 소스', '간장 양을 절반으로', '메밀 함량 확인 필요', '1인분, 15분']
    s2 = ''.join(f'<div class="it {"on" if i == 0 else ""}"><b>{t}</b><p>{d}  {previews[i]}</p></div>' for i, (t, d) in enumerate(CONVOS))
    steps = ''.join(f'<li>{s}</li>' for s in STEPS)
    frame = f'''<div class="nt"><div class="w3">
<aside class="s1"><div class="wm">Salus</div><ul>{s1}</ul>
<div class="pf">{USER}<br>알레르기 {', '.join(ALLERGIES)}<br>건강 정보 {HEALTH_TAGS[0]}</div></aside>
<section class="s2"><div class="sr">대화 검색</div><h6>이번 주</h6>{s2}</section>
<section class="s3"><div class="tb"><span class="l">오늘 오후 6:12</span><span>저장</span><span>식단에 기록</span><span>새 대화</span></div>
<div class="doc"><div class="m">나</div><div class="u">{Q}</div>
<div class="m">Salus</div><div class="a">{INTRO}</div>
<h1>{TITLE}</h1><div class="mt">{SERVES}, {TIME}, {NOTE}</div>
<h5>알레르기 확인 ({', '.join(ALLERGIES)})</h5>{checklist()}
<h5>건강 참고 ({HEALTH[0]})</h5><div>{HEALTH[1]}</div>
<h5>재료</h5>{ingr(INGR)}
<h5>만드는 법</h5><ol class="sp">{steps}</ol>
<div class="sm">바꾼 점: {', '.join(f"{a} {b}" for a, b in CHANGES)}<br>참고한 글: {SOURCES[0][2]}, {SOURCES[1][2]}<br>{DISCLAIMER}</div></div>
<div class="cmp"><span>바꾸고 싶은 점을 적어 주세요</span><b>보내기</b></div></section></div></div>'''
    return web_board(KEY, LABEL, CONCEPT, CSS, frame)


def app():
    sb = '<div class="sb"><span>9:41</span><span>●●● 100%</span></div>'
    bar = lambda on: '<nav class="abar">' + ''.join(f'<span class="{"on" if n == on else ""}">{n}</span>' for n in ['대화', '식단', '레시피', '내 정보']) + '</nav>'
    week = ''.join(f'<div class="it"><b>{t}</b><p>{d}</p></div>' for t, d in CONVOS[:2])
    older = ''.join(f'<div class="it"><b>{t}</b><p>{d}</p></div>' for t, d in CONVOS[2:5])
    home = f'''<div class="nt a g">{sb}<div class="ah"><div class="r"><span>{USER}</span><span>편집</span></div><h2>대화</h2><div class="sr">검색</div></div>
<div class="lbl">이번 주</div><div class="grp">{week}</div><div class="lbl">10월</div><div class="grp">{older}</div>
<div class="lbl" style="font-weight:400;line-height:1.6">{', '.join(ALLERGIES)} 알레르기, {HEALTH_TAGS[0]} 기준으로 확인해요.</div>
<div class="fab">새 대화</div>{bar('대화')}</div>'''
    res = f'''<div class="nt a">{sb}<div class="ad"><div class="top"><span>‹ 대화</span><span>저장</span></div>
<div class="m">나</div><div class="u">{Q}</div><div class="m">Salus</div><div class="a">{INTRO}</div>
<div class="doc" style="padding:0"><h1>{TITLE}</h1><div class="mt">{SERVES}, {TIME}, {NOTE}</div>
<h5>알레르기 확인</h5>{checklist()}<h5>건강 참고</h5><div>{HEALTH[1]}</div><h5>재료</h5>{ingr(INGR)}<h5>만드는 법</h5><ol class="sp">{''.join(f'<li>{s}</li>' for s in STEPS)}</ol><div class="sm">{DISCLAIMER}</div></div></div>
<div class="acmp"><span>바꾸고 싶은 점</span><b>보내기</b></div></div>'''
    rows = lambda xs: ''.join(f'<div class="it"><b>{t}</b><p>{s}, {tm}  {f"<em>{x}</em>" if k == "check" else x}</p></div>' for t, s, tm, x, k, d in xs)
    lst = f'''<div class="nt a g">{sb}<div class="ah"><div class="r"><span></span><span>정렬</span></div><h2>레시피</h2><div class="sr">검색</div></div>
<div class="lbl">이번 주</div><div class="grp">{rows(SAVED[:2])}</div><div class="lbl">이전</div><div class="grp">{rows(SAVED[2:])}</div>{bar('레시피')}</div>'''
    return app_board(KEY, LABEL, CONCEPT, CSS, [home, res, res.replace('class="ad"', 'class="ad" data-scroll="end"', 1), lst])


if __name__ == '__main__':
    print(web()); print(app())
