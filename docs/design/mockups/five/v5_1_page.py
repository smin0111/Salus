# 방향 1. 요리책 지면: 상자 없이 명조 제목, 가는 선, 여백으로만 위계를 만든다.
from v5_data import *

KEY, LABEL = '1-page', '1. 요리책 지면'
CONCEPT = '상자와 칩을 없애고, 인쇄된 레시피 한 페이지처럼 읽히게'
FONTS = "@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+KR:wght@500;700;900&display=swap');"

CSS = '''
.pg{--paper:#F7F3EA;--ink:#1B1F1C;--ink2:#575C57;--ink3:#7A7E78;--rule:#D3CABA;--forest:#0B3B32;--warn:#8A5300;--red:#B3261E;
  background:var(--paper);color:var(--ink);font-family:Pretendard,sans-serif;height:100%;position:relative}
.sf{font-family:'Noto Serif KR',serif}
/* web */
.pg.w{display:grid;grid-template-columns:248px 1fr}
.nav{border-right:1px solid var(--rule);padding:30px 26px;display:flex;flex-direction:column;height:900px}
.nav .wm{font:900 26px 'Noto Serif KR',serif;color:var(--forest);letter-spacing:-.5px}
.nav .new{margin-top:28px;font-size:15px;font-weight:700;color:var(--forest);text-decoration:underline;text-underline-offset:4px}
.nav h6{margin-top:34px;font-size:12.5px;font-weight:600;color:var(--ink3)}
.nav ul{list-style:none;margin-top:10px}
.nav li{display:flex;justify-content:space-between;gap:8px;padding:9px 0;font-size:14.5px;color:var(--ink2);border-bottom:1px solid #E7E0D2}
.nav li.on{color:var(--ink);font-weight:700}
.nav li small{font-size:12.5px;color:var(--ink3);font-weight:400;flex:none}
.nav .me{margin-top:auto;font-size:13.5px;line-height:1.65;color:var(--ink2)}
.nav .me b{color:var(--ink);font-weight:700}
.nav .me a{color:var(--forest);text-decoration:underline;text-underline-offset:3px}
.main{position:relative;height:900px;overflow:hidden}
.col{width:860px;margin:0 auto;padding:44px 0 160px}
.ask{font-size:16px;color:var(--ink2);line-height:1.6;padding-left:16px;border-left:2px solid var(--rule);margin-left:auto;width:fit-content;max-width:560px}
.intro{margin-top:30px;font-size:16.5px;line-height:1.75;max-width:700px}
.rt{margin-top:34px;font-size:44px;font-weight:900;letter-spacing:-1.4px;line-height:1.15}
.meta{margin-top:12px;display:flex;align-items:center;gap:14px;font-size:15px;color:var(--ink2)}
.meta i{width:1px;height:13px;background:var(--rule)}
.meta .act{margin-left:auto;display:flex;gap:22px;font-weight:700;color:var(--forest)}
.meta .act span{text-decoration:underline;text-underline-offset:4px}
hr{border:0;border-top:1px solid var(--ink);margin:22px 0 0}
.sec{display:grid;grid-template-columns:150px 1fr;gap:0 28px;padding:20px 0;border-bottom:1px solid var(--rule)}
.sec>h3{font:700 17px 'Noto Serif KR',serif;padding-top:1px}
.ck{display:grid;grid-template-columns:52px 1fr 90px;gap:16px;font-size:15px;line-height:1.65;padding:2px 0 10px}
.ck+.ck{padding-top:10px;border-top:1px dotted var(--rule)}
.ck b{font-weight:700}
.ck em{font-style:normal;font-weight:700;color:var(--warn);text-align:right}
.hl{font-size:15px;line-height:1.65}
.hl b{font-weight:700}
.two{display:grid;grid-template-columns:300px 1fr;gap:48px;padding-top:26px}
.two h3{font:700 19px 'Noto Serif KR',serif;margin-bottom:12px}
.ing li{list-style:none;display:flex;align-items:baseline;gap:6px;font-size:15px;padding:6px 0}
.ing li .dots{flex:1;border-bottom:1px dotted #B9B09F;transform:translateY(-4px)}
.ing li small{display:block;font-size:12.5px;color:var(--warn);margin-top:2px}
.stp li{list-style:none;display:grid;grid-template-columns:34px 1fr;font-size:15.5px;line-height:1.7;padding:6px 0}
.stp li b{font:700 20px 'Noto Serif KR',serif;color:var(--forest);line-height:1.35}
.tip{margin:10px 0 0 34px;font-size:14px;color:var(--ink2);line-height:1.6}
.foot{display:grid;grid-template-columns:1fr 1fr;gap:48px;margin-top:28px;padding-top:16px;border-top:1px solid var(--rule);font-size:13.5px;color:var(--ink2);line-height:1.7}
.foot h4{font-size:13px;font-weight:700;color:var(--ink);margin-bottom:4px}
.disc{margin-top:18px;font-size:12.5px;color:var(--ink3)}
.cmp{position:absolute;left:0;right:0;bottom:0;background:linear-gradient(transparent,var(--paper) 30%);padding:40px 0 28px}
.cmp div{width:860px;margin:0 auto;border-top:1px solid var(--ink);padding-top:16px;display:flex;justify-content:space-between;font-size:16px;color:var(--ink3)}
.cmp div b{color:var(--forest);font-weight:700}
/* app */
.pg.a{height:844px}
.a .top{display:flex;justify-content:space-between;align-items:baseline;padding:10px 24px 0}
.a .top .wm{font:900 22px 'Noto Serif KR',serif;color:var(--forest)}
.a .top a{font-size:14px;color:var(--forest);font-weight:700;text-decoration:underline;text-underline-offset:3px}
.tabs{position:absolute;bottom:0;left:0;right:0;height:84px;border-top:1px solid var(--rule);display:flex;justify-content:space-around;padding-top:14px;font-size:13.5px;color:var(--ink3);background:var(--paper)}
.tabs .on{color:var(--ink);font-weight:800}
.home{padding:110px 24px 0}
.home h2{font:900 34px 'Noto Serif KR',serif;letter-spacing:-1px;line-height:1.25}
.home .in{margin-top:26px;border-bottom:1.5px solid var(--ink);padding:10px 0;font-size:17px;color:var(--ink3);display:flex;justify-content:space-between}
.home .in b{color:var(--forest);font-size:15px}
.home .basis{margin-top:14px;font-size:13.5px;color:var(--ink2);line-height:1.6}
.home .basis a{color:var(--forest);font-weight:700;text-decoration:underline;text-underline-offset:3px}
.home h6{margin-top:60px;font-size:12.5px;color:var(--ink3);font-weight:600}
.home ul{list-style:none;margin-top:6px}
.home li{display:flex;justify-content:space-between;padding:13px 0;border-bottom:1px solid #E5DECF;font-size:15.5px}
.home li small{color:var(--ink3);font-size:13px}
.res{padding:6px 22px 110px;height:794px;overflow-y:auto}
.res .ask{font-size:14.5px;max-width:280px}
.res .intro{font-size:15px;margin-top:18px;line-height:1.7}
.res .rt{font-size:31px;margin-top:22px;letter-spacing:-1px}
.res .meta{font-size:13.5px;gap:10px;margin-top:8px}
.res .act2{display:flex;gap:18px;margin-top:14px;font-size:14px;font-weight:700;color:var(--forest)}
.res .act2 span{text-decoration:underline;text-underline-offset:3px}
.res hr{margin-top:16px}
.res h3{font:700 16px 'Noto Serif KR',serif;margin:16px 0 6px}
.res .ck{grid-template-columns:40px 1fr;font-size:14px;gap:10px;padding:6px 0}
.res .ck em{grid-column:2;text-align:left;font-size:13.5px;margin-top:-4px}
.res .hl{font-size:14px;padding-bottom:6px}
.res .ing li{font-size:14.5px;padding:5px 0}
.acmp{position:absolute;left:0;right:0;bottom:0;background:var(--paper);border-top:1px solid var(--ink);padding:14px 22px 36px;display:flex;justify-content:space-between;font-size:15px;color:var(--ink3)}
.acmp b{color:var(--forest)}
.lst{padding:8px 22px 0}
.lst h2{font:900 30px 'Noto Serif KR',serif;letter-spacing:-.8px;margin-top:8px}
.lst .flt{display:flex;gap:18px;margin-top:14px;font-size:14px;color:var(--ink3);padding-bottom:12px;border-bottom:1px solid var(--ink)}
.lst .flt .on{color:var(--ink);font-weight:800;text-decoration:underline;text-underline-offset:5px}
.lst .flt .s{margin-left:auto}
.lst .it{padding:15px 0 14px;border-bottom:1px solid #E2DBCC}
.lst .it .t{display:flex;justify-content:space-between;align-items:baseline}
.lst .it .t b{font:700 18px 'Noto Serif KR',serif}
.lst .it .t small{font-size:12.5px;color:var(--ink3)}
.lst .it p{font-size:13.5px;color:var(--ink2);margin-top:5px}
.lst .it p em{font-style:normal;font-weight:700}
.st-check{color:var(--warn)} .st-none{color:var(--ink2)} .st-unknown{color:var(--ink3)}
'''


def checks(app=False):
    return ''.join(f'<div class="ck"><b>{a}</b><span>{t}</span><em>{STATUS[s]}</em></div>' for a, s, t in CHECKS)


def ingr(items):
    out = ''
    for n, q, m in items:
        note = f'<small>{m}</small>' if m else ''
        out += f'<li><span>{n}{note}</span><span class="dots"></span><span>{q}</span></li>'
    return f'<ul class="ing">{out}</ul>'


def web():
    nav = ''.join(f'<li class="{"on" if i == 0 else ""}">{t}<small>{d}</small></li>' for i, (t, d) in enumerate(CONVOS))
    steps = ''.join(f'<li><b>{i}</b><span>{s}</span></li>' for i, s in enumerate(STEPS, 1))
    changes = '<br>'.join(f'{a} → {b}' for a, b in CHANGES)
    srcs = '<br>'.join(f'{k}, 「{t}」 {u}' for k, t, u in SOURCES)
    frame = f'''<div class="pg w">
<aside class="nav"><div class="wm">Salus</div><div class="new">새 대화</div><h6>지난 대화</h6><ul>{nav}</ul>
<div class="me"><b>{USER}</b><br>알레르기 {', '.join(ALLERGIES)}<br>건강 정보 {', '.join(HEALTH_TAGS)}<br><a>바꾸기</a></div></aside>
<main class="main"><div class="col">
<p class="ask">{Q}</p>
<p class="intro">{INTRO}</p>
<h1 class="rt sf">{TITLE}</h1>
<div class="meta">{SERVES}<i></i>{TIME}<i></i>{NOTE}<div class="act"><span>저장</span><span>식단에 기록</span></div></div>
<hr>
<div class="sec"><h3>알레르기 확인</h3><div>{checks()}</div></div>
<div class="sec"><h3>건강 참고</h3><p class="hl"><b>{HEALTH[0]}</b>으로 등록되어 있어요. {HEALTH[1]}</p></div>
<div class="two"><div><h3>재료 <span style="font:500 14px Pretendard;color:#7A7E78">{SERVES}</span></h3>{ingr(INGR)}</div>
<div><h3>만드는 법</h3><ol class="stp">{steps}</ol><p class="tip">{TIP}</p></div></div>
<div class="foot"><div><h4>원래 레시피에서 바꾼 점</h4>{changes}</div><div><h4>참고한 글</h4>{srcs}</div></div>
<p class="disc">{DISCLAIMER}</p>
</div>
<div class="cmp"><div><span>바꾸고 싶은 점을 적어 주세요</span><b>보내기</b></div></div>
</main></div>'''
    return web_board(KEY, LABEL, CONCEPT, CSS, frame, FONTS)


def app():
    sb = '<div class="sb"><span>9:41</span><span>●●● 100%</span></div>'
    tabs = '<nav class="tabs"><span class="on">대화</span><span>식단</span><span>레시피</span><span>내 정보</span></nav>'
    recent = ''.join(f'<li>{t}<small>{d}</small></li>' for t, d in CONVOS[:4])
    home = f'''<div class="pg a">{sb}<div class="top"><span class="wm">Salus</span><a>지난 대화</a></div>
<div class="home"><h2 class="sf">오늘은<br>무엇을 만들까요?</h2>
<div class="in"><span>요리 이름이나 재료를 적어 주세요</span><b>보내기</b></div>
<p class="basis">{', '.join(ALLERGIES)} 알레르기와 {HEALTH_TAGS[0]} 기준으로 확인해요. <a>바꾸기</a></p>
<h6>최근 대화</h6><ul>{recent}</ul></div>{tabs}</div>'''
    res = f'''<div class="pg a">{sb}<div class="res">
<p class="ask">{Q}</p><p class="intro">{INTRO}</p>
<h1 class="rt sf">{TITLE}</h1><div class="meta">{SERVES}<i></i>{TIME}<i></i>{NOTE}</div>
<div class="act2"><span>저장</span><span>식단에 기록</span></div><hr>
<h3>알레르기 확인</h3>{checks(True)}
<h3>건강 참고</h3><p class="hl"><b>{HEALTH[0]}</b>. {HEALTH[1]}</p>
<h3>재료</h3>{ingr(INGR)}<h3>만드는 법</h3><ol class="stp">{''.join(f'<li><b>{i}</b><span>{s}</span></li>' for i, s in enumerate(STEPS, 1))}</ol><p class="disc">{DISCLAIMER}</p></div>
<div class="acmp"><span>바꾸고 싶은 점을 적어 주세요</span><b>보내기</b></div></div>'''
    items = ''.join(f'<div class="it"><div class="t"><b>{t}</b><small>{d}</small></div><p>{s} {tm} <span style="color:#C9C1B1">|</span> <em class="st-{k}">{st}</em></p></div>'
                    for t, s, tm, st, k, d in SAVED)
    lst = f'''<div class="pg a">{sb}<div class="lst"><h2 class="sf">저장한 레시피</h2>
<div class="flt"><span class="on">전체 6</span><span>확인 필요 있음</span><span class="s">찾기</span></div>{items}</div>
<nav class="tabs"><span>대화</span><span>식단</span><span class="on">레시피</span><span>내 정보</span></nav></div>'''
    return app_board(KEY, LABEL, CONCEPT, CSS, [home, res, res.replace('class="res"', 'class="res" data-scroll="end"', 1), lst], FONTS)


if __name__ == '__main__':
    print(web()); print(app())
