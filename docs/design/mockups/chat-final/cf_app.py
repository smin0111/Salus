"""채팅 완성 시안 · 앱 8화면 (390×844)."""
import pathlib
from cf_lib import CSS, ic, tteok, mala, banner, health, tag, lockchip, logo

OUT = pathlib.Path(__file__).parent

ACSS = """
body{background:#D9D1C1}
.board{padding:40px 44px 50px}
.hd{display:flex;align-items:baseline;gap:14px}
.hd b{font-size:13px;font-weight:900;color:#fff;background:var(--forest);padding:5px 10px;border-radius:8px}
.hd span{font-size:24px;font-weight:800;letter-spacing:-.4px}
.hd em{font-style:normal;font-size:14.5px;color:var(--ink2)}
.row{display:flex;gap:40px;margin-top:28px}
.cap{width:390px}
.cap .t{font-size:16px;font-weight:900;margin-bottom:4px;display:flex;gap:8px;align-items:center}
.cap .t i{font-style:normal;font-size:12px;font-weight:900;color:var(--terra)}
.cap .desc{font-size:13px;color:var(--ink2);line-height:1.55;margin-bottom:14px;min-height:60px}
.phone{width:390px;height:844px;border-radius:50px;overflow:hidden;position:relative;background:var(--cream);box-shadow:0 0 0 9px #141816,0 0 0 10px #3a3f3c,0 34px 70px rgba(16,32,27,.28)}
.island{position:absolute;top:11px;left:50%;transform:translateX(-50%);width:120px;height:34px;border-radius:20px;background:#0b0d0c;z-index:20}
.sbar{height:54px;display:flex;justify-content:space-between;align-items:center;padding:6px 32px 0 36px;font-size:15.5px;font-weight:700;position:relative;z-index:25}
.sbar .r{display:flex;gap:6px;align-items:center}
.homei{position:absolute;bottom:8px;left:50%;transform:translateX(-50%);width:134px;height:5px;border-radius:3px;background:var(--ink);z-index:30}
.ah{display:flex;align-items:center;gap:8px;padding:2px 12px 8px;height:52px}
.ah .t{flex:1;text-align:center;font-size:15.5px;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.hb{width:42px;height:42px;border-radius:14px;display:flex;align-items:center;justify-content:center;color:var(--ink)}
.body{padding:6px 16px 0}
.comp{position:absolute;left:12px;right:12px;bottom:30px;background:var(--paper);border:1px solid var(--line);border-radius:28px;padding:7px 7px 7px 7px;display:flex;align-items:center;gap:8px;box-shadow:0 12px 30px rgba(16,32,27,.12)}
.comp .pl{width:40px;height:40px;border-radius:50%;background:var(--cream);display:flex;align-items:center;justify-content:center;color:var(--ink2);flex:none}
.comp .tx{flex:1;font-size:15px;color:var(--ink3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.sendb{width:42px;height:42px;border-radius:50%;background:var(--terra);color:#fff;display:flex;align-items:center;justify-content:center;flex:none}
.sendb.off{background:var(--cream2);color:var(--ink3)}
.stopb{height:42px;border-radius:999px;background:var(--ink);color:#fff;display:flex;align-items:center;gap:6px;padding:0 16px 0 12px;font-weight:800;font-size:14px;flex:none}
.tabbar{position:absolute;left:0;right:0;bottom:0;height:84px;background:var(--paper);border-top:1px solid var(--line);display:flex;padding:8px 10px 0}
.tabbar div{flex:1;display:flex;flex-direction:column;align-items:center;gap:4px;font-size:11px;font-weight:800;color:var(--ink3)}
.tabbar .on{color:var(--forest)}
.me{max-width:82%;font-size:15px}
.mini{display:flex;gap:6px;align-items:center;font-size:11.5px;color:var(--ink3);font-weight:700}
.rc{background:var(--paper);border:1px solid var(--line);border-radius:24px;padding:14px;box-shadow:0 8px 24px rgba(16,32,27,.06)}
.ac{display:flex;gap:6px;margin-top:10px}
.ac div{flex:1;height:38px;border-radius:12px;background:var(--paper);border:1px solid var(--line);display:flex;align-items:center;justify-content:center;gap:5px;font-size:12.5px;font-weight:800;color:var(--ink2)}
.cap .stp .d{min-height:0;margin-bottom:0;font-size:12px;color:inherit;line-height:1}
.cap .stp .d.ok{color:#fff}.cap .stp .d.on{color:var(--terra)}.cap .stp .d.no{color:var(--ink3)}
.dim{position:absolute;inset:0;background:rgba(16,32,27,.42);z-index:15}
"""

SB = (f'<div class="island"></div><div class="sbar"><span>9:41</span><span class="r">'
      '<svg width="18" height="12" viewBox="0 0 18 12"><rect x="0" y="8" width="3" height="4" rx="1" fill="currentColor"/><rect x="5" y="5.5" width="3" height="6.5" rx="1" fill="currentColor"/><rect x="10" y="3" width="3" height="9" rx="1" fill="currentColor"/><rect x="15" y="0" width="3" height="12" rx="1" fill="currentColor"/></svg>'
      '<svg width="26" height="13" viewBox="0 0 26 13"><rect x=".5" y=".5" width="22" height="12" rx="3.5" fill="none" stroke="currentColor" opacity=".45"/><rect x="2.5" y="2.5" width="16" height="8" rx="2" fill="currentColor"/><rect x="23.5" y="4.5" width="2" height="4" rx="1" fill="currentColor" opacity=".45"/></svg></span></div>')


def phone(no, title, desc, inner, sbstyle=''):
    return (f'<div class="cap"><div class="t"><i>{no}</i>{title}</div><div class="desc">{desc}</div>'
            f'<div class="phone"><div style="{sbstyle}">{SB}</div>{inner}<div class="homei"></div></div></div>')


def hdr(title, left='menu', right='pen', guest=False):
    r = ('<div style="font-size:14px;font-weight:800;color:var(--terra);padding:0 10px">로그인</div>' if guest
         else f'<div class="hb">{ic(right, 21)}</div>')
    return f'<div class="ah"><div class="hb">{ic(left, 22)}</div><div class="t">{title}</div>{r}</div>'


def comp(state='idle', text=''):
    tx = f'<div class="tx" style="color:var(--ink)">{text}</div>' if text else '<div class="tx">무엇이든 말해 주세요</div>'
    if state == 'gen':
        b = f'<div class="stopb">{ic("stop", 16)}멈춤</div>'
    else:
        b = f'<div class="sendb{"" if text else " off"}">{ic("up", 19, 2.6)}</div>'
    return f'<div class="comp"><div class="pl">{ic("plus", 19, 2.4)}</div>{tx}{b}</div>'


def acts(guest=False, saved=False):
    if guest:
        return ''
    h = ic('heartf', 15) if saved else ic('heart', 15)
    sv = f'<div style="color:var(--terraD);border-color:#F1C9BB">{h}저장됨</div>' if saved else f'<div>{h}저장</div>'
    return f'<div class="ac">{sv}<div>{ic("cal", 15)}식단</div><div>{ic("share", 15)}공유</div><div>{ic("vol", 15)}듣기</div></div>'


# A1 대화 목록 ------------------------------------------------------------------
def cv(t, on=False, menu=False):
    st = 'background:rgba(255,255,255,.12);font-weight:800;color:var(--cream)' if on else ('background:rgba(255,255,255,.07)' if menu else '')
    return f'<div style="display:flex;align-items:center;height:44px;padding:0 12px;border-radius:12px;font-size:15px;color:rgba(246,240,228,.85);{st}"><span style="flex:1">{t}</span></div>'


a1 = f'''<div style="position:absolute;inset:0;padding:54px 16px 0;opacity:.6">{hdr("버터 떡볶이 (우유 제외)")}</div>
<div class="dim"></div>
<div style="position:absolute;top:0;bottom:0;left:0;width:322px;background:var(--forest);color:var(--cream);padding:62px 14px 0;z-index:16;border-radius:0 26px 26px 0">
 <div style="display:flex;align-items:center;justify-content:space-between;padding:0 6px"><div style="display:flex;align-items:center;gap:10px;font-size:17px;font-weight:900;letter-spacing:3px">{logo(26)}SALUS</div>{ic("x", 22, 2, "opacity:.7")}</div>
 <div style="margin-top:18px;height:48px;border-radius:15px;background:var(--terra);color:#fff;display:flex;align-items:center;justify-content:center;gap:8px;font-weight:800;font-size:15px">{ic("plus", 18, 2.6)}새 대화</div>
 <div style="font-size:11.5px;font-weight:800;letter-spacing:.6px;color:rgba(246,240,228,.45);margin:20px 12px 6px">오늘</div>
 {cv("버터 떡볶이 (우유 제외)", on=True)}{cv("저염 된장찌개")}
 <div style="font-size:11.5px;font-weight:800;letter-spacing:.6px;color:rgba(246,240,228,.45);margin:16px 12px 6px">어제</div>
 {cv("두부 버섯 덮밥")}
 <div style="position:relative">{cv("아이 간식 땅콩 없이", menu=True)}
  <div style="position:absolute;left:56px;top:48px;width:214px;background:var(--paper);color:var(--ink);border-radius:18px;padding:6px;box-shadow:0 18px 40px rgba(0,0,0,.35);z-index:3">
   <div style="display:flex;gap:10px;align-items:center;padding:12px;font-size:14.5px;font-weight:800">{ic("pen", 17)}이름 바꾸기</div>
   <div style="height:1px;background:var(--line);margin:0 8px"></div>
   <div style="display:flex;gap:10px;align-items:center;padding:12px;font-size:14.5px;font-weight:800;color:#B83224">{ic("trash", 17)}대화 삭제</div>
  </div></div>
 <div style="font-size:11.5px;font-weight:800;letter-spacing:.6px;color:rgba(246,240,228,.45);margin:100px 12px 6px">지난 7일</div>
 {cv("마라샹궈 집에서")}{cv("연어 포케")}
 <div style="position:absolute;left:14px;right:14px;bottom:30px;border-top:1px solid rgba(255,255,255,.1);padding:14px 6px 0;display:flex;gap:10px;align-items:center">
  <div style="width:34px;height:34px;border-radius:50%;background:#E9D5B7;color:var(--forest);display:flex;align-items:center;justify-content:center;font-weight:900">승</div>
  <div style="flex:1"><div style="font-size:14px;font-weight:800">승민</div><div style="font-size:11.5px;opacity:.55">무료 · 새 요리 찾기 4번 남음</div></div></div>
</div>'''

# A2 첫 화면 --------------------------------------------------------------------
sug = [('#FBE7DF', 'var(--terraD)', 'flame', '요즘 유행하는 두쫀쿠', '처음 보는 요리'), ('#FCF1DA', '#8A5A0E', 'heartbeat', '국물 적은 저염 된장찌개', '내 건강 기준'),
       ('#DCEBE3', 'var(--forest)', 'clock', '두부랑 애호박으로 15분', '빠르게')]
sl = ''.join(f'<div style="display:flex;gap:12px;align-items:center;background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:11px 12px">'
             f'<div style="width:36px;height:36px;border-radius:11px;background:{bg};color:{fg};display:flex;align-items:center;justify-content:center">{ic(i, 18, 2.2)}</div>'
             f'<div style="flex:1"><div style="font-size:11px;font-weight:800;color:{fg}">{k}</div><div style="font-size:14.5px;font-weight:800;margin-top:1px">{t}</div></div>{ic("chevr", 18, 2.2, "color:var(--ink3)")}</div>' for bg, fg, i, t, k in sug)
a2 = f'''{hdr("새 대화")}
<div class="body" style="padding:14px 22px 0">
 <div style="display:flex;justify-content:space-between;align-items:center"><div class="kick">승민님, 저녁 시간이에요</div>
 <div style="display:flex;gap:5px;align-items:center;font-size:11.5px;font-weight:800;color:var(--ink2);background:var(--paper);border:1px solid var(--line);padding:5px 10px;border-radius:999px">{ic("spark", 12, 2.4)}4번 남음</div></div>
 <div class="mega" style="font-size:44px;margin-top:12px">오늘 뭐<br>먹고 싶어요?</div>
 <div style="margin-top:20px;display:flex;justify-content:space-between;align-items:center"><div style="display:flex;gap:6px;align-items:center;font-size:12px;font-weight:800;color:var(--ink3)">{ic("shield", 14, 2.2)}항상 적용되는 내 기준</div><div style="font-size:12px;font-weight:800;color:var(--terra)">수정</div></div>
 <div style="display:flex;gap:6px;margin-top:8px">{lockchip("갑각류")}{lockchip("땅콩")}{lockchip("우유")}</div>
 <div style="font-size:12px;color:var(--ink2);margin-top:8px;display:flex;gap:6px;align-items:center">{ic("heartbeat", 13, 2.2)}고혈압 · 와파린 → 건강 주의로 안내</div>
 <div style="display:flex;flex-direction:column;gap:8px;margin-top:18px">{sl}</div>
</div>
<div class="comp" style="bottom:98px"><div class="pl">{ic("plus", 19, 2.4)}</div><div class="tx">요리 이름이나 재료를 말해 주세요</div><div class="sendb off">{ic("up", 19, 2.6)}</div></div>
<div class="tabbar"><div>{ic("home", 22)}오늘</div><div class="on">{ic("spark", 22, 2.2)}만들기</div><div>{ic("book", 22)}레시피</div><div>{ic("user", 22)}나</div></div>'''

# A3 생성 중 --------------------------------------------------------------------
a3 = f'''{hdr("새 대화")}
<div class="body">
 <div class="me">요즘 유행하는 버터 떡볶이 만들어 줘</div><div class="meta">오후 6:12</div>
 <div class="rc" style="margin-top:14px;padding:12px 16px 6px">
  <div style="display:flex;align-items:center;gap:8px;margin-bottom:2px"><div class="av" style="width:24px;height:24px;border-radius:8px">{logo(13)}</div><div style="font-size:14px;font-weight:900;flex:1">레시피를 만들고 있어요</div><div style="font-size:12px;color:var(--ink3)">14초</div></div>
  <div class="stp done"><div class="d ok">{ic("check", 13, 3)}</div><div><div class="t">출처 찾기</div><div class="x">웹 레시피 3곳 · 새 요리 찾기 1번 사용</div></div></div>
  <div class="stp done"><div class="d ok">{ic("check", 13, 3)}</div><div><div class="t">재료 정리</div><div class="x">재료 9개 · 버터 → 식물성 버터 (우유)</div></div></div>
  <div class="stp"><div class="d on"><div class="ring"></div>3</div><div style="flex:1"><div class="t">내 알레르기 검사 <span style="color:var(--terra);font-size:12.5px">5/9</span></div><div class="x">갑각류 · 땅콩 · 우유 기준으로 확인 중</div><div class="bar"><i style="width:56%"></i></div></div></div>
  <div class="stp"><div class="d no">4</div><div><div class="t" style="color:var(--ink3)">건강 주의 붙이기</div><div class="x">고혈압 · 와파린 기준</div></div></div>
 </div>
 <div style="display:flex;gap:12px;margin-top:12px;align-items:center"><div class="sk" style="width:64px;height:64px;border-radius:16px"></div><div style="flex:1"><div class="sk" style="width:90px;height:14px"></div><div class="sk" style="width:200px;height:18px;margin-top:8px"></div></div></div>
</div>
<div style="position:absolute;left:20px;right:20px;bottom:96px;display:flex;gap:6px;justify-content:center;font-size:11.5px;color:var(--ink2)">{ic("err", 13, 2.2)}지금 보내면 이 답을 멈추고 새 말과 함께 답해요</div>
{comp("gen")}'''

# A4 멈춘 뒤 --------------------------------------------------------------------
a4 = f'''{hdr("새 대화")}
<div class="body">
 <div class="me" style="opacity:.75">요즘 유행하는 버터 떡볶이 만들어 줘</div>
 <div style="display:flex;justify-content:flex-end;margin-top:6px"><span class="tg2 tz" style="height:24px">{ic("stop", 11)}멈춤 · 다음 메시지와 함께 답해요</span></div>
 <div style="margin-top:18px;display:flex;gap:10px;background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:13px 14px;font-size:13.5px;line-height:1.55;color:var(--ink2)">
  {ic("err", 17, 2.2, "color:var(--ink3);margin-top:1px")}<div>답을 멈췄어요. 이어서 보내는 말은 <b style="color:var(--ink)">위 질문과 함께</b> 이해해서 답해요. 전혀 다른 요리라면 <b style="color:var(--ink)">새 대화</b>로 시작해 주세요.</div></div>
 <div class="me" style="margin-top:20px">그거 2인분 말고 4인분으로</div>
 <div class="meta" style="display:flex;gap:5px;justify-content:flex-end;align-items:center">{ic("link", 11, 2.4)}위 질문과 함께 보냄 · 오후 6:13</div>
 <div style="display:flex;gap:10px;align-items:center;margin-top:16px"><div class="av" style="width:26px;height:26px;border-radius:9px">{logo(14)}</div>
  <div style="display:flex;gap:5px">{''.join(f'<i style="width:7px;height:7px;border-radius:50%;background:var(--terra);opacity:{o};display:block"></i>' for o in (1, .6, .3))}</div>
  <div style="font-size:13px;color:var(--ink2)">버터 떡볶이 4인분으로 출처를 찾는 중</div></div>
</div>
{comp("gen")}'''

# A5 결과 ----------------------------------------------------------------------
a5 = f'''{hdr("버터 떡볶이 (우유 제외)")}
<div class="body">
 <div class="me">요즘 유행하는 버터 떡볶이 만들어 줘</div>
 <div class="mini" style="margin:12px 2px 8px">{ic("check", 13, 2.8, "color:var(--grn)")}출처 3곳 · 재료 9개 · 알레르기 · 건강 주의 확인</div>
 <div class="rc">
  {banner("red", "어묵 — 등록한 갑각류와 겹쳐요", "어묵에는 새우 등 갑각류가 들어갈 수 있어요.", compact=True)}
  <div style="height:6px"></div>{health("고혈압 — 고추장을 1큰술로 줄였어요.", compact=True)}
  <div style="display:flex;gap:12px;align-items:center;margin-top:12px">{tteok(64)}<div style="flex:1;min-width:0"><div style="font-size:18px;font-weight:900;letter-spacing:-.4px;line-height:1.25">버터 떡볶이<br><span style="font-size:14px;color:var(--ink2);font-weight:800">우유 제외</span></div>
   <div style="font-size:12px;color:var(--ink2);margin-top:4px;display:flex;gap:10px"><span style="display:flex;gap:3px;align-items:center">{ic("clock", 12, 2.2)}20분</span><span style="display:flex;gap:3px;align-items:center">{ic("users", 12, 2.2)}2인분</span><span>버터 → 식물성</span></div></div></div>
  <div style="display:flex;flex-wrap:wrap;gap:5px;margin-top:10px">{tag("r", "어묵")}{tag("y", "떡 원재료")}{tag("f", "식물성 버터")}<span class="tg2 tz">+6</span></div>
  <div style="display:flex;gap:8px;margin-top:12px"><div class="btn b-terra" style="flex:1;height:42px;font-size:13.5px;padding:0">{ic("retry", 15, 2.6)}어묵 빼고 다시</div><div class="btn b-forest" style="flex:1;height:42px;font-size:13.5px;padding:0">전체 보기{ic("chevr", 15, 2.6)}</div></div>
 </div>
 {acts()}
 <div class="disc" style="margin-top:8px;text-align:center">의료 조언이 아니에요 · 원재료는 직접 확인해 주세요</div>
</div>
{comp()}'''

# A6 전체 보기 시트 ------------------------------------------------------------------
def ingr(name, q, t='', hit=False):
    return f'<div class="ingr{" hit" if hit else ""}" style="font-size:14.5px"><div class="n">{name}{t}</div><div class="q">{q}</div></div>'


a6 = f'''<div style="position:absolute;inset:0;padding:54px 0 0;opacity:.5">{hdr("버터 떡볶이 (우유 제외)")}</div>
<div class="dim"></div>
<div style="position:absolute;left:0;right:0;top:64px;bottom:0;background:var(--paper);border-radius:28px 28px 0 0;padding:8px 18px 0;overflow:hidden;z-index:16">
 <div style="width:40px;height:5px;border-radius:3px;background:var(--line);margin:0 auto 12px"></div>
 {banner("red", "어묵 — 등록한 갑각류와 겹쳐요", "", f'<div class="btn b-terra" style="height:32px;font-size:12.5px;padding:0 12px">{ic("retry", 13, 2.6)}어묵 빼고 다시</div>', compact=True)}
 <div style="height:6px"></div>{health("고혈압 — 고추장을 1큰술로 줄였어요.", compact=True)}
 <div style="display:flex;gap:12px;align-items:center;margin-top:14px">{tteok(70)}<div><div class="mega" style="font-size:24px;letter-spacing:-.8px">버터 떡볶이</div><div style="font-size:12.5px;color:var(--ink2);margin-top:4px;display:flex;gap:10px"><span>우유 제외</span><span>20분</span><span>2인분</span><span>출처 3곳</span></div></div></div>
 <div class="tabs" style="margin-top:12px"><div class="on">재료<span>9</span></div><div>만드는 법<span>6</span></div><div>출처<span>3</span></div></div>
 <div class="gh">주재료</div>
 {ingr("떡볶이 떡", "300g", tag("y", "원재료 확인"))}
 {ingr("어묵", "2장", tag("r", "갑각류"), hit=True)}
 {ingr("대파", "1대")}
 <div class="gh">양념</div>
 {ingr("식물성 버터", "1큰술", tag("f", "버터 대신"))}
 {ingr("고추장", "1큰술")}
 <div style="position:absolute;left:0;right:0;bottom:0;padding:8px 16px 30px;background:var(--paper);border-top:1px solid var(--line)">
  <div class="disc" style="text-align:center;margin-bottom:8px">의료 조언이 아니에요 · 원재료는 직접 확인해 주세요</div>
  <div style="display:flex;gap:8px"><div class="btn b-soft" style="width:50px;padding:0">{ic("heart", 19)}</div><div class="btn b-soft" style="width:50px;padding:0">{ic("cal", 19)}</div><div class="btn b-forest" style="flex:1;height:44px">{ic("play", 15)}조리 모드 시작</div></div>
 </div>
</div>'''

# A7 고친 결과 + 저장 ---------------------------------------------------------------
diff = [('minus', '#B83224', '어묵 2장 뺐어요'), ('swap', 'var(--forest)', '고추장 1 → ½큰술'), ('plus', 'var(--forest)', '양배추 ⅛통 추가')]
dl = ''.join(f'<div style="display:flex;gap:8px;align-items:center;font-size:13px;font-weight:800;padding:4px 0"><span style="width:22px;height:22px;border-radius:7px;background:var(--cream);color:{c};display:flex;align-items:center;justify-content:center">{ic(i, 12, 3)}</span>{t}</div>' for i, c, t in diff)
a7 = f'''{hdr("버터 떡볶이 (우유 제외)")}
<div class="body">
 <div class="me">어묵 빼고 조금 덜 맵게 해 줘</div><div class="meta">오후 6:14</div>
 <div style="display:flex;gap:10px;align-items:flex-start;margin-top:12px"><div class="av" style="width:26px;height:26px;border-radius:9px">{logo(14)}</div>
  <div class="ai" style="font-size:14.5px;line-height:1.55">어묵을 빼고 덜 맵게 고쳤어요. 바뀐 재료로 알레르기를 다시 검사했어요.</div></div>
 <div style="margin:10px 0 0 36px;background:var(--paper);border:1px solid var(--line);border-radius:16px;padding:8px 12px">{dl}</div>
 <div class="rc" style="margin-top:10px">
  {banner("yel", "시판 떡의 원재료를 확인하지 못했어요", "포장의 알레르기 표시를 직접 확인해 주세요.", compact=True)}
  <div style="display:flex;gap:12px;align-items:center;margin-top:12px">{tteok(58, True)}<div style="flex:1"><div style="font-size:17px;font-weight:900;letter-spacing:-.4px">버터 떡볶이 · 어묵 없이</div>
   <div style="display:flex;gap:5px;margin-top:6px">{tag("y", "떡 원재료")}{tag("f", "양배추")}</div></div>{ic("chevr", 20, 2.2, "color:var(--ink3)")}</div>
 </div>
 {acts(saved=True)}
</div>
<div class="toast" style="position:absolute;left:16px;right:16px;bottom:100px">{ic("check", 16, 2.8, "color:var(--mint)")}<span style="flex:1">내 레시피에 저장했어요</span><a>보기</a></div>
{comp(text="에어프라이어로도 돼?")}'''

# A8 비회원 --------------------------------------------------------------------
a8 = f'''{hdr("둘러보기", left="chevl", guest=True)}
<div class="body">
 <div class="me">마라샹궈 집에서 만들어 줘</div>
 <div class="rc" style="margin-top:14px">
  {banner("yel", "내 알레르기 정보가 없어 확인하지 않았어요", "로그인하고 동의한 뒤 내 기준을 등록하면 재료마다 확인해 드려요.", compact=True)}
  <div style="display:flex;gap:12px;align-items:center;margin-top:12px">{mala(64)}<div style="flex:1"><div style="font-size:18px;font-weight:900;letter-spacing:-.4px">집에서 만드는 마라샹궈</div>
   <div style="font-size:12px;color:var(--ink2);margin-top:4px">30분 · 2인분 · 출처 2곳</div></div></div>
  <div style="display:flex;flex-wrap:wrap;gap:5px;margin-top:10px"><span class="tg2 tz">마라 소스</span><span class="tg2 tz">새우</span><span class="tg2 tz">땅콩</span><span class="tg2 tz">청경채</span><span class="tg2 tz">+6</span></div>
  <div style="font-size:11.5px;color:var(--ink3);margin-top:6px">회색 꼬리표는 판정 없이 재료 이름만 보여 줘요</div>
  <div class="btn b-forest" style="width:100%;height:42px;font-size:13.5px;margin-top:12px">전체 보기{ic("chevr", 15, 2.6)}</div>
 </div>
 <div style="margin-top:12px;background:var(--forest);color:var(--cream);border-radius:20px;padding:14px 16px">
  <div style="font-size:14.5px;font-weight:900">로그인하면 내 알레르기로 확인해요</div>
  <div style="font-size:12.5px;opacity:.75;margin-top:4px;line-height:1.5">저장 · 식단 기록 · 듣기도 쓸 수 있어요</div>
  <div style="display:flex;gap:8px;margin-top:10px;align-items:center"><div class="btn" style="height:36px;background:var(--cream);color:var(--forest);font-size:13px">로그인</div><div style="font-size:11.5px;opacity:.6">처음 보는 요리 찾기 오늘 2번 남음</div></div>
 </div>
</div>
{comp()}'''


def board(n, title, sub, phones, name):
    html = (f'<!doctype html><html lang="ko"><head><meta charset="utf-8"><link rel="stylesheet" href="key.css"><style>{CSS}{ACSS}</style></head><body>'
            f'<div class="board" style="width:1818px"><div class="hd"><b>앱 {n}</b><span>{title}</span><em>{sub}</em></div><div class="row">{"".join(phones)}</div></div></body></html>')
    (OUT / name).write_text(html, encoding='utf-8')


board('1', '목록 · 첫 화면 · 생성 중 · 멈춘 뒤', '기다리는 동안 무엇을 확인하는지 보여 주고, 멈춰도 입력창을 빼앗지 않습니다.', [
    phone('A1', '대화 목록', '☰로 여는 숲색 시트. 길게 누르면 이름 바꾸기 · 삭제. 아래에 남은 횟수.', a1, 'color:var(--cream)'),
    phone('A2', '첫 화면', '큰 질문과 예시 3개. 내 기준은 끌 수 없는 잠긴 칩. 대화가 시작되면 탭바를 숨겨 입력 공간을 넓혀요.', a2),
    phone('A3', '생성 중', '실제 확인 단계를 보여 줘요. 입력창은 그대로, 비어 있으면 멈춤 · 입력하면 전송.', a3),
    phone('A4', '멈춘 뒤', '멈춘 질문에 꼬리표. 이어 보낸 말에는 "위 질문과 함께 보냄"을 붙여 결과를 예측할 수 있게.', a4)], 'cf-app-1.html')
board('2', '결과 · 전체 보기 · 고친 결과 · 비회원', '결과는 대화 속 카드로 짧게, 자세한 내용은 시트로. 비회원은 개인별 판정을 하지 않는다는 걸 분명히.', [
    phone('A5', '결과 카드', '배너 → 건강 주의 → 요약 → 꼬리표. 해결 버튼과 전체 보기, 아래에 저장 · 식단 · 공유 · 듣기.', a5),
    phone('A6', '전체 보기', '아래에서 올라오는 시트. 배너와 해결 버튼이 맨 위. 충돌 재료 줄은 붉게 강조. 하단에 조리 모드.', a6),
    phone('A7', '고친 결과 · 저장', '바뀐 점 3줄을 먼저 보여 주고 다시 검사. 충돌이 사라져도 확인 못 한 재료가 있으면 "확인 필요".', a7),
    phone('A8', '비회원 결과', '배너는 항상 "확인 필요", 꼬리표는 회색. 저장 · 식단 · 듣기 대신 로그인 안내.', a8)], 'cf-app-2.html')
print('ok')
