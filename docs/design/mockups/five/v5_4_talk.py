# 방향 4. 대화 그대로: 승인된 숲·크림·테라 팔레트를 유지하되, 레시피를 카드 대신 짧은 메시지 여러 개로 나눠 보낸다.
from v5_data import *

KEY, LABEL = '4-talk', '4. 대화 그대로'
CONCEPT = '승인된 색은 그대로. 레시피를 카드 한 장 대신 메시지 몇 개로 나눠 말하듯이'

CSS = '''
.tk{--cream:#F6F0E4;--paper:#FFFBF3;--forest:#0B3B32;--ink:#10201B;--ink2:#4F5B55;--ink3:#646B66;--line:#E2D9C7;--terra:#C2502F;--warn:#8A5300;
  background:var(--cream);color:var(--ink);font-family:Pretendard,sans-serif;height:100%;position:relative}
.w4{display:grid;grid-template-columns:260px 1fr;height:900px}
.sd{background:var(--forest);color:#E9E3D6;padding:28px 22px;position:relative}
.sd .wm{font-size:22px;font-weight:900;letter-spacing:-.5px;color:#fff}
.sd .nw{margin-top:24px;height:42px;border:1px solid rgba(255,255,255,.35);border-radius:21px;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:15px;color:#fff}
.sd h6{margin-top:30px;font-size:12.5px;color:rgba(233,227,214,.62);font-weight:600}
.sd li{list-style:none;padding:9px 0;font-size:14.5px;display:flex;justify-content:space-between;color:rgba(233,227,214,.82)}
.sd li.on{color:#fff;font-weight:700}
.sd li small{color:rgba(233,227,214,.55);font-size:12.5px}
.sd .pf{position:absolute;left:22px;right:22px;bottom:24px;font-size:13px;line-height:1.6;color:rgba(233,227,214,.75);border-top:1px solid rgba(255,255,255,.18);padding-top:14px}
.sd .pf b{color:#fff}
.ch{position:relative;overflow-y:auto;height:900px}
.ch .cmp{position:sticky}
.th{width:720px;margin:0 auto;padding:36px 0 150px;display:flex;flex-direction:column;gap:10px}
.me{align-self:flex-end;background:var(--forest);color:#fff;padding:12px 16px;border-radius:20px 20px 6px 20px;font-size:15.5px;line-height:1.55;max-width:440px}
.who{font-size:12.5px;color:var(--ink3);font-weight:700;margin-top:14px}
.bt{font-size:16px;line-height:1.75;max-width:640px}
.bt + .bt{margin-top:6px}
.bt b{font-weight:800}
.bt p+p{margin-top:8px}
.bt .w{color:var(--warn);font-weight:800}
.bt h2{font-size:26px;font-weight:900;letter-spacing:-.6px;margin-bottom:2px}
.bt .sub{font-size:14.5px;color:var(--ink2)}
.bt ul{list-style:none;margin-top:4px}
.bt ul li{padding:1px 0}
.bt ul li span{color:var(--ink2)}
.bt ul li small{color:var(--warn);font-size:13px;margin-left:6px;font-weight:600}
.bt ol{margin:4px 0 0 20px}
.bt ol li{padding:2px 0}
.bt .tip{font-size:14.5px;color:var(--ink2)}
.qa{display:flex;gap:16px;margin-top:6px;font-size:14.5px;font-weight:700;color:var(--forest)}
.qa span{text-decoration:underline;text-underline-offset:4px}
.qa .t{color:var(--terra)}
.small{font-size:13px;color:var(--ink3);line-height:1.6;margin-top:4px}
.cmp{position:absolute;left:0;right:0;bottom:0;padding:24px 0 26px;background:linear-gradient(transparent,var(--cream) 28%)}
.cmp div{width:720px;margin:0 auto;height:56px;border-radius:28px;background:var(--paper);border:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 8px 0 22px;color:var(--ink3);font-size:15.5px}
.cmp b{height:42px;padding:0 20px;border-radius:21px;background:var(--terra);color:#fff;display:flex;align-items:center;font-size:15px}
/* app */
.tk.a{height:844px}
.ap{padding:4px 20px;display:flex;justify-content:space-between;align-items:center}
.ap .wm{font-size:20px;font-weight:900;color:var(--forest)}
.ap a{font-size:14px;color:var(--forest);font-weight:700}
.hm{padding:0 20px}
.hm .ttl{font-size:28px;font-weight:900;letter-spacing:-.8px;margin-top:150px;line-height:1.3}
.hm .bs{margin-top:10px;font-size:14px;color:var(--ink2);line-height:1.6}
.hm .bs u{color:var(--forest);font-weight:700}
.hm .rc{margin-top:36px;font-size:14px;color:var(--ink2);line-height:2}
.hm .rc b{display:block;font-size:12.5px;color:var(--ink3)}
.hm .rc span{color:var(--ink);text-decoration:underline;text-decoration-color:var(--line);text-underline-offset:4px}
.acmp{position:absolute;left:14px;right:14px;bottom:30px;height:52px;border-radius:26px;background:var(--paper);border:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 6px 0 18px;color:var(--ink3);font-size:15px}
.acmp b{height:40px;padding:0 16px;border-radius:20px;background:var(--terra);color:#fff;display:flex;align-items:center;font-size:14.5px}
.at{padding:4px 16px 24px;height:672px;overflow-y:auto;display:flex;flex-direction:column;gap:8px}
.at .bt{font-size:15px;line-height:1.7}
.at .bt h2{font-size:22px}
.at .me{font-size:14.5px;max-width:280px}
.ls{padding:6px 20px}
.ls h2{font-size:28px;font-weight:900;letter-spacing:-.6px}
.ls .f{margin-top:10px;font-size:14px;color:var(--ink3);display:flex;gap:16px}
.ls .f .on{color:var(--forest);font-weight:800}
.ls .it{padding:14px 0;border-bottom:1px solid var(--line)}
.ls .it b{font-size:16.5px;display:block}
.ls .it p{font-size:13.5px;color:var(--ink2);margin-top:3px}
.ls .it em{font-style:normal;font-weight:700;color:var(--warn)}
.ls .it .g{color:var(--ink3)}
.nav{position:absolute;left:0;right:0;bottom:0;height:84px;background:var(--paper);border-top:1px solid var(--line);display:flex;justify-content:space-around;padding-top:14px;font-size:13px;color:var(--ink3)}
.nav .on{color:var(--forest);font-weight:800}
'''


def msgs(app=False):
    ck = ''.join(f'<p><b>{a}</b> <span class="w">{STATUS[s]}</span><br>{t}</p>' for a, s, t in CHECKS)
    ing = INGR
    il = ''.join(f'<li>{n} <span>{q}</span>{f"<small>{m}</small>" if m else ""}</li>' for n, q, m in ing)
    out = [f'<div class="me">{Q}</div>', '<div class="who">Salus</div>',
           f'<div class="bt">{INTRO}</div>',
           f'<div class="bt"><h2>{TITLE}</h2><div class="sub">{SERVES}, {TIME}, {NOTE}</div>{"" if app else '<div class="qa"><span>저장</span><span>식단에 기록</span></div>'}</div>',
           f'<div class="bt">{ck}</div>',
           f'<div class="bt">{HEALTH[0]}이 등록되어 있어서 덧붙여요. {HEALTH[1]}</div>',
           f'<div class="bt"><b>재료</b><ul>{il}</ul></div>']
    if True:
        steps = ''.join(f'<li>{s}</li>' for s in STEPS)
        out += [f'<div class="bt"><b>만드는 법</b><ol>{steps}</ol><p class="tip">{TIP}</p></div>',
                f'<div class="qa"><span>저장</span><span>식단에 기록</span></div>' if app else '',
                f'<p class="small">참고한 글 {SOURCES[0][2]}, {SOURCES[1][2]}. {DISCLAIMER}</p>']
    return ''.join(out)


def web():
    li = ''.join(f'<li class="{"on" if i == 0 else ""}">{t}<small>{d}</small></li>' for i, (t, d) in enumerate(CONVOS))
    frame = f'''<div class="tk"><div class="w4"><aside class="sd"><div class="wm">Salus</div><div class="nw">새 대화</div>
<h6>지난 대화</h6><ul>{li}</ul><div class="pf"><b>{USER}</b><br>알레르기 {', '.join(ALLERGIES)}, {HEALTH_TAGS[0]}</div></aside>
<main class="ch"><div class="th">{msgs()}</div><div class="cmp"><div><span>답장하듯 바꾸고 싶은 점을 적어 주세요</span><b>보내기</b></div></div></main></div></div>'''
    return web_board(KEY, LABEL, CONCEPT, CSS, frame)


def app():
    sb = '<div class="sb"><span>9:41</span><span>●●● 100%</span></div>'
    nav = lambda on: '<nav class="nav">' + ''.join(f'<span class="{"on" if n == on else ""}">{n}</span>' for n in ['대화', '식단', '레시피', '내 정보']) + '</nav>'
    rc = ''.join(f'<span>{t}</span><br>' for t, d in CONVOS[:3])
    home = f'''<div class="tk a">{sb}<div class="ap"><span class="wm">Salus</span><a>지난 대화</a></div>
<div class="hm"><div class="ttl">뭐 만들어<br>볼까요?</div><p class="bs">{', '.join(ALLERGIES)} 알레르기와 {HEALTH_TAGS[0]}을 보고 답해요. <u>바꾸기</u></p>
<div class="rc"><b>이어서 하기</b>{rc}</div></div>
<div class="acmp" style="bottom:104px"><span>예: 로제떡볶이 2인분, 덜 맵게</span><b>보내기</b></div>{nav('대화')}</div>'''
    res = f'''<div class="tk a">{sb}<div class="ap"><a>‹ 대화</a><a>저장</a></div><div class="at">{msgs(True)}</div>
<div class="acmp"><span>바꾸고 싶은 점</span><b>보내기</b></div></div>'''
    it = ''.join(f'<div class="it"><b>{t}</b><p>{s}, {tm}, {d}  <em class="{"g" if k != "check" else ""}" style="{"" if k=="check" else "color:#646B66;font-weight:500"}">{x}</em></p></div>' for t, s, tm, x, k, d in SAVED)
    lst = f'''<div class="tk a">{sb}<div class="ls"><h2>저장한 레시피</h2><div class="f"><span class="on">전체</span><span>확인 필요 있음</span><span>검사 기록 없음</span></div>{it}</div>{nav('레시피')}</div>'''
    return app_board(KEY, LABEL, CONCEPT, CSS, [home, res, res.replace('class="at"', 'class="at" data-scroll="end"', 1), lst])


if __name__ == '__main__':
    print(web()); print(app())
