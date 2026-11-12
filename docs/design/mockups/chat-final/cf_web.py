"""채팅 완성 시안 · 웹 4장 (1440×900 창)."""
import pathlib
from cf_lib import CSS, ic, tteok, banner, health, tag, lockchip, logo

OUT = pathlib.Path(__file__).parent

WCSS = """
body{background:#D9D1C1}
.stage{padding:34px 40px 40px;width:1520px}
.cap2{display:flex;align-items:baseline;gap:14px;margin-bottom:16px}
.cap2 b{font-size:13px;font-weight:900;color:#fff;background:var(--forest);padding:5px 10px;border-radius:8px}
.cap2 span{font-size:20px;font-weight:800;letter-spacing:-.3px}
.cap2 em{font-style:normal;font-size:14px;color:var(--ink2)}
.win{width:1440px;height:900px;border-radius:14px;overflow:hidden;background:var(--cream);box-shadow:0 0 0 1px #bdb4a2,0 40px 80px rgba(16,32,27,.22);display:flex;flex-direction:column}
.chrome{height:40px;background:#E4DCCD;display:flex;align-items:center;gap:8px;padding:0 16px;flex:none}
.chrome i{width:12px;height:12px;border-radius:50%;display:block}
.chrome .url{margin-left:22px;background:#F4EEE2;border-radius:8px;height:26px;width:440px;font-size:12.5px;color:var(--ink2);display:flex;align-items:center;gap:8px;padding:0 12px}
.app{flex:1;display:flex;min-height:0}
.side{width:276px;background:var(--forest);color:var(--cream);padding:18px 14px 14px;display:flex;flex-direction:column;flex:none}
.sh{display:flex;align-items:center;justify-content:space-between;padding:0 6px}
.lg{display:flex;align-items:center;gap:10px;font-size:17px;font-weight:900;letter-spacing:3px}
.snav{margin-top:18px;display:flex;flex-direction:column;gap:2px}
.snav div{display:flex;align-items:center;gap:12px;height:40px;padding:0 10px;border-radius:11px;font-size:14.5px;font-weight:700;color:rgba(246,240,228,.72)}
.snav .on{background:rgba(255,255,255,.1);color:var(--cream)}
.newc{margin-top:14px;height:46px;border-radius:14px;background:var(--terra);color:#fff;display:flex;align-items:center;justify-content:center;gap:8px;font-weight:800;font-size:15px}
.grp{font-size:11.5px;font-weight:800;letter-spacing:.6px;color:rgba(246,240,228,.45);margin:18px 10px 6px}
.cv{display:flex;align-items:center;gap:8px;height:38px;padding:0 10px;border-radius:10px;font-size:14px;color:rgba(246,240,228,.82)}
.cv span{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cv.on{background:rgba(255,255,255,.12);color:var(--cream);font-weight:700}
.cv.hv{background:rgba(255,255,255,.06)}
.crit{margin-top:auto;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.08);border-radius:16px;padding:13px 13px 12px}
.crit .t{display:flex;justify-content:space-between;align-items:center;font-size:12px;font-weight:800;color:rgba(246,240,228,.6)}
.crit .t a{color:var(--butter);font-weight:800}
.prof{display:flex;align-items:center;gap:10px;margin-top:12px;padding:6px}
.pa{width:34px;height:34px;border-radius:50%;background:#E9D5B7;color:var(--forest);display:flex;align-items:center;justify-content:center;font-weight:900}
.main{flex:1;min-width:0;display:flex;flex-direction:column;position:relative}
.top{height:62px;display:flex;align-items:center;gap:12px;padding:0 28px;border-bottom:1px solid var(--line);flex:none}
.top .tt{font-size:15.5px;font-weight:800;display:flex;align-items:center;gap:8px}
.quota{margin-left:auto;display:flex;align-items:center;gap:10px;background:var(--paper);border:1px solid var(--line);border-radius:999px;height:34px;padding:0 14px 0 12px;font-size:12.5px;font-weight:800;color:var(--ink2)}
.qd{display:flex;gap:3px}.qd i{width:8px;height:8px;border-radius:50%;background:var(--terra);display:block}.qd i.u{background:var(--line)}
.scroll{flex:1;overflow:hidden;position:relative}
.col{width:720px;margin:0 auto}
.comp{background:var(--paper);border-radius:24px;border:1px solid var(--line);box-shadow:0 18px 44px rgba(16,32,27,.10)}
.comp .ta{padding:18px 22px 6px;font-size:16.5px;color:var(--ink3);min-height:58px}
.comp .tb{display:flex;align-items:center;gap:8px;padding:8px 10px 10px 16px}
.sendb{width:44px;height:44px;border-radius:50%;background:var(--terra);color:#fff;display:flex;align-items:center;justify-content:center;flex:none}
.sendb.off{background:var(--cream2);color:var(--ink3)}
.stopb{height:44px;border-radius:999px;background:var(--ink);color:#fff;display:flex;align-items:center;gap:8px;padding:0 18px 0 14px;font-weight:800;font-size:14px;flex:none}
.foot{text-align:center;font-size:11.5px;color:var(--ink3);padding:9px 0 12px}
.sugc{background:var(--paper);border:1px solid var(--line);border-radius:20px;padding:16px 16px 15px;display:flex;flex-direction:column;gap:10px}
.sugc .ico{width:38px;height:38px;border-radius:12px;display:flex;align-items:center;justify-content:center}
.sugc .tt{font-size:15.5px;font-weight:800;line-height:1.35}
.sugc .ss{font-size:12.5px;color:var(--ink2);line-height:1.45}
.rcard{background:var(--paper);border-radius:22px;border:1px solid var(--line);padding:16px;display:flex;gap:16px;align-items:center}
.rcard.sel{border:2px solid var(--forest);box-shadow:0 10px 30px rgba(11,59,50,.12)}
.panel{width:476px;flex:none;background:var(--paper);border-left:1px solid var(--line);display:flex;flex-direction:column;min-height:0}
.ph{height:62px;display:flex;align-items:center;gap:10px;padding:0 18px 0 24px;border-bottom:1px solid var(--line);flex:none}
.ib{width:36px;height:36px;border-radius:11px;display:flex;align-items:center;justify-content:center;color:var(--ink2)}
.pb{flex:1;overflow:hidden;padding:18px 24px 0}
.pf{border-top:1px solid var(--line);padding:10px 20px 16px;flex:none;background:var(--paper)}
.mrow{display:flex;gap:14px;font-size:13px;color:var(--ink2);margin-top:8px}
.mrow span{display:flex;align-items:center;gap:5px}
.fu{display:inline-flex;align-items:center;gap:6px;height:34px;padding:0 13px;border-radius:999px;background:var(--paper);border:1px dashed #CDBFA6;font-size:13px;font-weight:700;color:var(--ink)}
"""


def window(url, body):
    return (f'<div class="win"><div class="chrome"><i style="background:#E5675A"></i><i style="background:#E8B04A"></i><i style="background:#62B85A"></i>'
            f'<div class="url">{ic("lock", 12, 2.4)}{url}</div></div><div class="app">{body}</div></div>')


def sidebar(active=None, hover=None, guest=False):
    nav = [('home', '오늘'), ('spark', '만들기'), ('book', '레시피'), ('user', '나')]
    h = (f'<div class="side"><div class="sh"><div class="lg">{logo(28)}SALUS</div><div class="ib" style="color:rgba(246,240,228,.6)">{ic("side", 18)}</div></div>'
         '<div class="snav">' + ''.join(f'<div class="{"on" if n == "만들기" else ""}">{ic(i, 18)}{n}</div>' for i, n in nav) + '</div>'
         f'<div class="newc">{ic("plus", 18, 2.6)}새 대화</div>')
    groups = [('오늘', ['버터 떡볶이 (우유 제외)', '저염 된장찌개']), ('어제', ['두부 버섯 덮밥', '아이 간식 땅콩 없이']),
              ('지난 7일', ['마라샹궈 집에서', '연어 포케', '닭가슴살 샐러드'])]
    for g, items in groups:
        h += f'<div class="grp">{g}</div>'
        for it in items:
            cls = 'on' if it == active else ('hv' if it == hover else '')
            tail = ic('more', 16) if it in (active, hover) else ''
            h += f'<div class="cv {cls}"><span>{it}</span>{tail}</div>'
    h += f'''<div class="crit"><div class="t"><span style="display:flex;gap:6px;align-items:center">{ic("shield", 14, 2.2)}항상 적용되는 내 기준</span><a>수정</a></div>
<div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:10px">{lockchip("갑각류", True)}{lockchip("땅콩", True)}{lockchip("우유", True)}</div>
<div style="display:flex;gap:6px;align-items:center;font-size:12px;color:rgba(246,240,228,.62);margin-top:10px">{ic("heartbeat", 13, 2.2)}고혈압 · 와파린 복용 → 건강 주의로 안내</div></div>
<div class="prof"><div class="pa">승</div><div style="flex:1"><div style="font-size:14px;font-weight:800">승민</div><div style="font-size:11.5px;color:rgba(246,240,228,.55);margin-top:1px">무료 이용 중</div></div>{ic("more", 18)}</div></div>'''
    return h


def quota(left, total=5):
    dots = ''.join(f'<i class="{"" if k < left else "u"}"></i>' for k in range(total))
    return f'<div class="quota">{ic("spark", 14, 2.2)}새 요리 찾기 오늘 {left}번 남음<div class="qd">{dots}</div></div>'


def composer(state='idle', text=''):
    lock = f'<span style="display:flex;gap:6px;align-items:center">{lockchip("갑각류")}{lockchip("땅콩")}{lockchip("우유")}<span class="fu" style="height:28px;font-size:12.5px">{ic("plus", 13, 2.4)}이번만 조건</span></span>'
    ph = text or '요리 이름이나 재료를 말해 주세요'
    color = 'var(--ink)' if text else 'var(--ink3)'
    if state == 'gen':
        btn = f'<div class="stopb">{ic("stop", 18)}멈춤</div>'
    else:
        btn = f'<div class="sendb{"" if text else " off"}">{ic("up", 20, 2.6)}</div>'
    return f'<div class="comp"><div class="ta" style="color:{color}">{ph}</div><div class="tb">{lock}<div style="flex:1"></div>{btn}</div></div>'


def page(n, title, sub, url, body, name):
    html = (f'<!doctype html><html lang="ko"><head><meta charset="utf-8"><link rel="stylesheet" href="key.css">'
            f'<style>{CSS}{WCSS}</style></head><body><div class="stage"><div class="cap2"><b>웹 {n}</b><span>{title}</span><em>{sub}</em></div>'
            f'{window(url, body)}</div></body></html>')
    (OUT / name).write_text(html, encoding='utf-8')


# ① 첫 화면 -----------------------------------------------------------------
sug = [('#FBE7DF', 'var(--terraD)', 'flame', '처음 보는 요리', '요즘 유행하는 두쫀쿠 만들어 줘', '웹에서 출처를 찾아 정리해요'),
       ('#FCF1DA', '#8A5A0E', 'heartbeat', '내 건강 기준', '국물 적은 저염 된장찌개', '고혈압 기준으로 간을 줄여요'),
       ('#DCEBE3', 'var(--forest)', 'clock', '빠르게', '두부랑 애호박으로 15분 요리', '있는 재료로 바로 만들어요')]
sugs = ''.join(f'''<div class="sugc"><div style="display:flex;justify-content:space-between;align-items:center"><div class="ico" style="background:{bg};color:{fg}">{ic(i, 20, 2.2)}</div>
<span style="font-size:11.5px;font-weight:800;color:{fg}">{k}</span></div><div class="tt">{t}</div><div class="ss">{s}</div></div>''' for bg, fg, i, k, t, s in sug)
w1 = sidebar(hover='저염 된장찌개') + f'''<div class="main">
<div class="top"><div class="tt">새 대화</div>{quota(4)}</div>
<div class="scroll" style="display:flex;flex-direction:column;justify-content:center;align-items:center">
 <div class="col" style="text-align:left;margin-top:-20px">
  <div class="kick">승민님, 저녁 시간이에요</div>
  <div class="mega" style="font-size:66px;margin-top:12px">오늘 뭐 먹고 싶어요?</div>
  <div style="font-size:17px;color:var(--ink2);margin-top:14px;line-height:1.55">요리 이름만 말해도 출처를 찾아 정리하고, 등록한 알레르기 성분이 재료에 있는지 하나씩 확인해요.</div>
  <div style="margin-top:30px">{composer()}</div>
  <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;margin-top:20px">{sugs}</div>
 </div>
</div>
<div class="foot">의료 진단이나 치료를 대신하지 않아요. 알레르기 성분과 원재료는 직접 확인해 주세요.</div>
</div>'''
page('1', '첫 화면', '큰 질문 하나와 입력창. 내 기준은 입력창 안에 잠긴 칩으로 늘 보입니다.', 'salus.app/chat', w1, 'cf-web-1.html')

# ② 생성 중 -----------------------------------------------------------------
src = ''.join(f'<span class="tg2 tz" style="height:24px">{ic("link", 11, 2.6)}{s}</span>' for s in ['식품안전나라', '웹 레시피 A', '웹 레시피 B'])
w2 = sidebar(active='새 대화') + f'''<div class="main">
<div class="top"><div class="tt">새 대화</div>{quota(4)}</div>
<div class="scroll"><div class="col" style="padding-top:34px">
 <div class="me">요즘 유행하는 버터 떡볶이 만들어 줘</div><div class="meta">오후 6:12</div>
 <div style="display:flex;gap:14px;margin-top:22px">
  <div class="av">{logo(16)}</div>
  <div style="flex:1">
   <div style="display:flex;align-items:center;gap:10px"><div style="font-size:15px;font-weight:800">레시피를 만들고 있어요</div><div style="font-size:12.5px;color:var(--ink3)">14초</div></div>
   <div style="background:var(--paper);border:1px solid var(--line);border-radius:20px;padding:10px 18px 8px;margin-top:12px">
    <div class="stp done"><div class="d ok">{ic("check", 14, 3)}</div><div style="flex:1"><div class="t">출처 찾기</div><div class="x">처음 보는 요리라 웹에서 찾았어요 · 새 요리 찾기 1번 사용</div><div style="display:flex;gap:6px;margin-top:8px">{src}</div></div></div>
    <div class="stp done"><div class="d ok">{ic("check", 14, 3)}</div><div><div class="t">재료 정리</div><div class="x">재료 9개 · 2인분 · 버터는 우유 성분이라 식물성 버터로 바꿨어요</div></div></div>
    <div class="stp"><div class="d on"><div class="ring"></div>3</div><div style="flex:1"><div class="t">내 알레르기 검사</div><div class="x">갑각류 · 땅콩 · 우유 기준으로 재료를 하나씩 확인하는 중</div><div class="bar" style="width:260px"><i style="width:56%"></i></div></div><div style="font-size:12.5px;font-weight:800;color:var(--terra)">5 / 9</div></div>
    <div class="stp"><div class="d no">4</div><div><div class="t" style="color:var(--ink3)">건강 주의 붙이기</div><div class="x">고혈압 · 와파린 기준</div></div></div>
   </div>
   <div style="display:flex;gap:16px;margin-top:14px;align-items:center;opacity:.9">
    <div class="sk" style="width:88px;height:88px;border-radius:18px"></div>
    <div style="flex:1"><div class="sk" style="width:120px;height:18px"></div><div class="sk" style="width:260px;height:22px;margin-top:10px"></div><div class="sk" style="width:180px;height:14px;margin-top:10px"></div></div>
   </div>
  </div>
 </div>
</div></div>
<div class="col" style="padding-top:8px">{composer('gen')}<div style="display:flex;gap:6px;align-items:center;justify-content:center;font-size:12px;color:var(--ink2);margin-top:9px">{ic("err", 13, 2.2)}답을 만드는 중이에요. 지금 보내면 이 답을 멈추고, 이 질문과 새 말을 함께 이해해서 답해요.</div></div>
<div class="foot">의료 진단이나 치료를 대신하지 않아요. 알레르기 성분과 원재료는 직접 확인해 주세요.</div>
</div>'''
page('2', '생성 중', '"생각 중…" 대신 지금 확인하는 단계를 보여 줍니다. 입력창은 그대로, 비어 있으면 멈춤.', 'salus.app/chat', w2, 'cf-web-2.html')

# 공통: 레시피 패널 -----------------------------------------------------------
def ingr(name, q, t='', hit=False):
    return f'<div class="ingr{" hit" if hit else ""}"><div class="n">{name}{t}</div><div class="q">{q}</div></div>'


def panel(rev=False):
    if rev:
        top = (banner('yel', '시판 떡의 원재료를 확인하지 못했어요', '포장의 알레르기 표시에서 갑각류 · 땅콩 · 우유가 있는지 직접 확인해 주세요.')
               + '<div style="height:8px"></div>' + health('고혈압 — 고추장을 ½큰술로 더 줄였어요. 간장은 넣지 않아도 돼요.'))
        badge = f'<span class="tg2 tf" style="height:24px">{ic("pen", 11, 2.6)}방금 고침 · 어묵 빼기 · 덜 맵게</span>'
        body = f'''<div class="tabs" style="margin-top:16px"><div>재료<span>9</span></div><div class="on">만드는 법<span>6</span></div><div>출처<span>3</span></div></div>
<div class="step"><b>1</b><div>떡은 찬물에 10분 불려 물기를 빼 주세요. 굳은 떡이면 끓는 물에 30초 데쳐요.<div class="tm">{ic("clock", 12, 2.6)}10분</div></div></div>
<div class="step"><b>2</b><div>팬에 식물성 버터 1큰술을 녹이고 대파와 다진 마늘을 약불에서 볶아 향을 내요.</div></div>
<div class="step"><b>3</b><div>물 200ml, 고추장 ½큰술, 설탕 1큰술을 넣고 잘 풀어 끓여요.</div></div>
<div class="step"><b>4</b><div>떡과 양배추를 넣고 중불에서 국물이 반으로 줄 때까지 졸여요.<div class="tm">{ic("clock", 12, 2.6)}5분</div></div></div>'''
        foot_save = f'<div class="btn b-soft" style="flex:1;color:var(--terraD)">{ic("heartf", 17)}저장됨</div>'
    else:
        top = (banner('red', '어묵 — 등록한 갑각류와 겹쳐요', '어묵에는 새우 등 갑각류가 들어갈 수 있어요.',
                      f'<div class="btn b-terra" style="height:36px;font-size:13px;padding:0 14px">{ic("retry", 14, 2.6)}어묵 빼고 다시 만들기</div>')
               + '<div style="height:8px"></div>' + health('고혈압 — 떡볶이 양념은 나트륨이 많아요. 고추장을 1큰술로 줄였어요.'))
        badge = f'<span class="tg2 tf" style="height:24px">{ic("swap", 11, 2.6)}버터 → 식물성 버터 (우유)</span>'
        body = f'''<div class="tabs" style="margin-top:16px"><div class="on">재료<span>9</span></div><div>만드는 법<span>6</span></div><div>출처<span>3</span></div></div>
<div class="gh">주재료</div>
{ingr('떡볶이 떡', '300g', tag('y', '원재료 확인'))}
{ingr('어묵', '2장', tag('r', '갑각류'), hit=True)}
{ingr('대파', '1대')}
<div class="gh">양념</div>
{ingr('식물성 버터', '1큰술', tag('f', '버터 대신'))}
{ingr('고추장', '1큰술')}
{ingr('설탕', '1큰술')}'''
        foot_save = f'<div class="btn b-soft" style="flex:1">{ic("heart", 17)}저장</div>'
    return f'''<div class="panel">
<div class="ph"><div style="font-size:13px;font-weight:900;color:var(--ink3);letter-spacing:.4px;flex:1">레시피</div><div class="ib">{ic("copy", 18)}</div><div class="ib">{ic("vol", 18)}</div><div class="ib">{ic("x", 18)}</div></div>
<div class="pb">{top}
 <div style="display:flex;gap:16px;align-items:center;margin-top:18px">{tteok(92, rev)}<div><div class="mega" style="font-size:27px;letter-spacing:-.9px;line-height:1.15">버터 떡볶이<br><span style="font-size:19px;color:var(--ink2);letter-spacing:-.4px">우유 제외{" · 어묵 없이" if rev else ""}</span></div>
 <div class="mrow"><span>{ic("clock", 14, 2.2)}20분</span><span>{ic("users", 14, 2.2)}2인분</span><span>{ic("link", 14, 2.2)}출처 3곳</span></div></div></div>
 <div style="margin-top:12px">{badge}</div>
 {body}
</div>
<div class="pf"><div style="font-size:11.5px;color:var(--ink3);text-align:center;margin-bottom:9px">의료 조언이 아니에요 · 알레르기 성분과 원재료는 직접 확인해 주세요</div>
<div style="display:flex;gap:8px">{foot_save}<div class="btn b-soft" style="flex:1">{ic("cal", 17)}식단에 추가</div><div class="btn b-forest" style="flex:1.25">{ic("play", 15)}조리 모드</div></div></div>
</div>'''


def acts(saved=False):
    h = ic('heartf', 15) if saved else ic('heart', 15)
    return (f'<div style="display:flex;gap:6px;margin-top:10px"><div class="ghost"{' style="color:var(--terraD);border-color:#F1C9BB"' if saved else ''}>{h}{"저장됨" if saved else "저장"}</div>'
            f'<div class="ghost">{ic("cal", 15)}식단에 추가</div><div class="ghost">{ic("copy", 15)}복사</div><div class="ghost">{ic("vol", 15)}듣기</div></div>')


def rcard(rev=False, sel=True, dim=False):
    t = (tag('y', '떡 원재료 확인') + tag('f', '양배추 추가')) if rev else (tag('r', '충돌 · 어묵') + tag('y', '떡 원재료 확인'))
    sub = '20분 · 2인분 · 어묵 빼고 덜 맵게 고친 버전' if rev else ('20분 · 2인분 · 처음 만든 버전' if dim else '20분 · 2인분 · 출처 3곳 · 오른쪽에서 보는 중')
    st = 'opacity:.55;' if dim else ''
    return (f'<div class="rcard{" sel" if sel else ""}" style="{st}">{tteok(84, rev)}<div style="flex:1;min-width:0"><div style="display:flex;gap:6px">{t}</div>'
            f'<div style="font-size:18.5px;font-weight:900;margin-top:8px;letter-spacing:-.3px">버터 떡볶이 (우유 제외{" · 어묵 없이" if rev else ""})</div>'
            f'<div style="font-size:13px;color:var(--ink2);margin-top:4px">{sub}</div></div>{ic("chevr", 20, 2.2, "color:var(--ink3)")}</div>')


# ③ 결과 -------------------------------------------------------------------
w3 = sidebar(active='버터 떡볶이 (우유 제외)') + f'''<div class="main">
<div class="top"><div class="tt">버터 떡볶이 (우유 제외){ic("pen", 15, 2.2, "color:var(--ink3)")}</div>{quota(4)}</div>
<div class="scroll"><div style="width:660px;margin:0 auto;padding-top:26px">
 <div class="me">요즘 유행하는 버터 떡볶이 만들어 줘</div><div class="meta">오후 6:12</div>
 <div style="display:flex;gap:14px;margin-top:18px">
  <div class="av">{logo(16)}</div>
  <div style="flex:1;min-width:0">
   <div style="display:flex;gap:6px;align-items:center;font-size:12px;color:var(--ink3);font-weight:700">{ic("check", 13, 2.8, "color:var(--grn)")}출처 3곳 · 재료 9개 · 알레르기 검사 · 건강 주의 확인 · 31초</div>
   <div class="ai" style="margin:10px 0 12px">웹 레시피 3곳을 바탕으로 정리했어요. 버터에는 우유 성분이 있어서 <b>식물성 버터</b>로 바꿨어요. 다만 <b style="color:#B83224">어묵이 등록한 갑각류와 겹쳐요.</b></div>
   {rcard()}
   {acts()}
   <div style="display:flex;gap:6px;margin-top:16px;flex-wrap:wrap"><span class="fu">{ic("retry", 13, 2.4)}어묵 빼고 다시</span><span class="fu">덜 맵게</span><span class="fu">4인분으로</span><span class="fu">에어프라이어로</span></div>
  </div>
 </div>
</div></div>
<div style="width:660px;margin:0 auto;padding-top:8px">{composer()}</div>
<div class="foot">의료 진단이나 치료를 대신하지 않아요. 알레르기 성분과 원재료는 직접 확인해 주세요.</div>
</div>''' + panel()
page('3', '결과 · 레시피 패널', '대화에는 요약 카드만, 전체 레시피는 오른쪽 패널. 배너 → 건강 주의 → 레시피 순서는 바뀌지 않습니다.', 'salus.app/chat/c/2041', w3, 'cf-web-3.html')

# ④ 고친 결과 + 저장 토스트 ------------------------------------------------------
diff = [('minus', '#B83224', '어묵 2장', '등록한 갑각류와 겹쳐서 뺐어요'), ('swap', 'var(--forest)', '고추장 1큰술 → ½큰술', '덜 맵게, 나트륨도 줄었어요'),
        ('plus', 'var(--forest)', '양배추 ⅛통', '어묵 대신 씹는 맛을 채워요')]
dl = ''.join(f'<div style="display:flex;gap:10px;align-items:center;padding:9px 0;{"border-top:1px solid var(--line);" if k else ""}"><div style="width:26px;height:26px;border-radius:8px;background:var(--cream);color:{c};display:flex;align-items:center;justify-content:center">{ic(i, 14, 2.8)}</div><div style="flex:1;font-size:14px;font-weight:800">{a}</div><div style="font-size:12.5px;color:var(--ink2)">{b}</div></div>' for k, (i, c, a, b) in enumerate(diff))
w4 = sidebar(active='버터 떡볶이 (우유 제외)') + f'''<div class="main">
<div class="top"><div class="tt">버터 떡볶이 (우유 제외){ic("pen", 15, 2.2, "color:var(--ink3)")}</div>{quota(4)}</div>
<div class="scroll"><div style="width:660px;margin:0 auto;padding-top:0">
 <div style="display:flex;gap:14px;margin-top:-18px">
  <div style="width:30px"></div><div style="flex:1">{rcard(sel=False, dim=True)}<div style="font-size:12px;color:var(--ink3);margin-top:6px;font-weight:700">이전 버전</div></div>
 </div>
 <div class="me" style="margin-top:22px">어묵 빼고 조금 덜 맵게 해 줘</div><div class="meta">오후 6:14</div>
 <div style="display:flex;gap:14px;margin-top:16px">
  <div class="av">{logo(16)}</div>
  <div style="flex:1;min-width:0">
   <div class="ai" style="margin-bottom:10px">어묵을 빼고 덜 맵게 고쳤어요. 바뀐 재료로 알레르기를 <b>다시 검사</b>했어요.</div>
   <div style="background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:6px 16px;margin-bottom:12px">{dl}</div>
   {rcard(rev=True)}
   {acts(saved=True)}
  </div>
 </div>
</div></div>
<div style="position:absolute;left:50%;transform:translateX(-50%);bottom:172px" class="toast">{ic("check", 16, 2.8, "color:var(--mint)")}내 레시피에 저장했어요<a>보기</a></div>
<div style="width:660px;margin:0 auto;padding-top:8px">{composer(text='이거 에어프라이어로도 돼?')}</div>
<div class="foot">의료 진단이나 치료를 대신하지 않아요. 알레르기 성분과 원재료는 직접 확인해 주세요.</div>
</div>''' + panel(rev=True)
page('4', '고친 결과 · 저장', '무엇이 바뀌었는지 먼저 보여 주고 다시 검사합니다. 충돌이 사라져도 확인 못 한 재료가 있으면 "확인 필요".', 'salus.app/chat/c/2041', w4, 'cf-web-4.html')
print('ok')
