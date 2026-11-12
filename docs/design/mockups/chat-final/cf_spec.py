"""채팅 완성 시안 · 상태와 컴포넌트 명세 보드."""
import pathlib
from cf_lib import CSS, ic, banner, health, tag, lockchip, logo

OUT = pathlib.Path(__file__).parent

SCSS = """
body{background:#D9D1C1}
.board{padding:40px 44px 50px;width:1818px}
.hd{display:flex;align-items:baseline;gap:14px}
.hd b{font-size:13px;font-weight:900;color:#fff;background:var(--forest);padding:5px 10px;border-radius:8px}
.hd span{font-size:24px;font-weight:800;letter-spacing:-.4px}
.hd em{font-style:normal;font-size:14.5px;color:var(--ink2)}
.grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:20px;margin-top:26px}
.card{background:var(--cream);border-radius:22px;padding:22px 22px 20px}
.card h3{font-size:16px;font-weight:900;display:flex;gap:8px;align-items:center}
.card h3 i{font-style:normal;font-size:12px;color:var(--terra);font-weight:900}
.card .sub{font-size:12.5px;color:var(--ink2);margin-top:4px;line-height:1.5}
.lbl{font-size:11px;font-weight:900;color:var(--ink3);letter-spacing:.5px;margin:14px 0 6px;display:flex;justify-content:space-between}
.lbl em{font-style:normal;color:var(--ink2);font-weight:700;letter-spacing:0}
.cmp{background:var(--paper);border:1px solid var(--line);border-radius:26px;padding:6px;display:flex;align-items:center;gap:8px}
.cmp .pl{width:38px;height:38px;border-radius:50%;background:var(--cream);display:flex;align-items:center;justify-content:center;color:var(--ink2);flex:none}
.cmp .tx{flex:1;font-size:14.5px;color:var(--ink3)}
.sendb{width:40px;height:40px;border-radius:50%;background:var(--terra);color:#fff;display:flex;align-items:center;justify-content:center;flex:none}
.sendb.off{background:var(--cream2);color:var(--ink3)}
.stopb{height:40px;border-radius:999px;background:var(--ink);color:#fff;display:flex;align-items:center;gap:6px;padding:0 15px 0 11px;font-weight:800;font-size:13.5px;flex:none}
.hint{font-size:11.5px;color:var(--ink2);margin:6px 0 0 14px;display:flex;gap:5px;align-items:center}
.pri{display:flex;gap:6px;align-items:center;margin-top:12px;flex-wrap:wrap}
.pri span{font-size:11.5px;font-weight:900;padding:5px 9px;border-radius:8px}
.cond{font-size:12px;color:var(--ink2);line-height:1.5;margin:5px 0 0 4px}
.sw{display:flex;gap:10px;flex-wrap:wrap;margin-top:12px}
.sw div{width:96px}
.sw b{display:block;height:54px;border-radius:12px;border:1px solid rgba(0,0,0,.06)}
.sw span{display:block;font-size:11.5px;font-weight:800;margin-top:6px}
.sw small{display:block;font-size:10.5px;color:var(--ink3);font-family:ui-monospace,Menlo,monospace}
.tyrow{display:flex;align-items:baseline;gap:14px;padding:7px 0;border-bottom:1px solid var(--line)}
.tyrow small{width:90px;font-size:11px;color:var(--ink3);font-family:ui-monospace,Menlo,monospace;flex:none}
.ghost{height:32px}
"""


def cmp(state, text='', hint=''):
    tx = f'<div class="tx" style="color:var(--ink)">{text}</div>' if text else '<div class="tx">무엇이든 말해 주세요</div>'
    if state == 'gen':
        b = f'<div class="stopb">{ic("stop", 15)}멈춤</div>'
    elif state == 'off':
        b = f'<div class="sendb off">{ic("up", 18, 2.6)}</div>'
    else:
        b = f'<div class="sendb">{ic("up", 18, 2.6)}</div>'
    h = f'<div class="hint">{ic("err", 12, 2.2)}{hint}</div>' if hint else ''
    return f'<div class="cmp"><div class="pl">{ic("plus", 18, 2.4)}</div>{tx}{b}</div>{h}'


# 1 안전 배너 --------------------------------------------------------------
c1 = f'''<div class="card" style="grid-row:span 2"><h3><i>01</i>안전 배너 4가지</h3>
<div class="sub">서버 검사 결과와 프로필 상태를 함께 봅니다. 근거가 하나라도 빠지면 "확인 필요". 색만으로 구분하지 않고 아이콘 + 단어 + 문장을 함께 씁니다.</div>
<div class="pri"><span style="background:#FBEAE7;color:#B83224">1 · 충돌</span>{ic("chevr", 14, 2.4, "color:var(--ink3)")}<span style="background:#FCF1DA;color:#8A5A0E">2 · 확인 필요</span>{ic("chevr", 14, 2.4, "color:var(--ink3)")}<span style="background:#EFEDE7;color:#5A605C">3 · 검사 대상 없음</span>{ic("chevr", 14, 2.4, "color:var(--ink3)")}<span style="background:#E4F1E8;color:#276A43">4 · 찾지 못함</span></div>
<div class="lbl">1 · 충돌<em>검토 안 된 출처여도 먼저 표시</em></div>
{banner("red", "어묵 — 등록한 갑각류와 겹쳐요", "어묵에는 새우 등 갑각류가 들어갈 수 있어요.", f'<div class="btn b-terra" style="height:34px;font-size:12.5px;padding:0 13px">{ic("retry", 13, 2.6)}어묵 빼고 다시 만들기</div>')}
<div class="lbl">2 · 확인 필요<em>미동의 · 조회 실패 · 검사 미완료 · 못 읽은 재료 · UNKNOWN · 비회원</em></div>
{banner("yel", "시판 떡의 원재료를 확인하지 못했어요", "포장의 알레르기 표시에서 직접 확인해 주세요.")}
<div style="height:8px"></div>
{banner("yel", "내 알레르기 정보가 없어 확인하지 않았어요", "로그인하고 동의한 뒤 내 기준을 등록하면 확인해 드려요.", compact=True)}
<div class="lbl">3 · 검사 대상 없음<em>등록한 알레르기 0개</em></div>
{banner("gry", "등록한 알레르기가 없어 검사하지 않았어요", "", f'<span style="font-size:12.5px;font-weight:800;color:var(--forest);display:inline-flex;gap:4px;align-items:center">내 기준 등록하기{ic("chevr", 13, 2.6)}</span>')}
<div class="lbl">4 · 찾지 못함<em>동의 + 프로필 조회 + 1개 이상 + 검사 완료일 때만</em></div>
{banner("grn", "등록한 알레르기 성분을 재료에서 찾지 못했어요", "원재료 표시와 교차 오염은 직접 확인해 주세요.")}
<div style="margin-top:12px;display:flex;gap:8px;align-items:flex-start;background:var(--paper);border-radius:12px;padding:10px 12px;font-size:12px;line-height:1.5;color:var(--ink2)">{ic("x", 14, 2.6, "color:#B83224;margin-top:2px")}<div>"안전합니다", "먹어도 돼요"는 쓰지 않아요. 건강 주의는 배너와 섞지 않고 바로 아래 따로 둡니다.</div></div>
<div style="margin-top:10px">{health("고혈압 — 떡볶이 양념은 나트륨이 많아요. 고추장을 1큰술로 줄였어요.")}</div>
</div>'''

# 2 입력창 상태 -----------------------------------------------------------------
c2 = f'''<div class="card"><h3><i>02</i>입력창 상태</h3><div class="sub">생성 중에도 입력창은 그대로 둡니다. 버튼만 바뀌어요.</div>
<div class="lbl">기본 · 빈 입력<em>전송 비활성</em></div>{cmp("off")}
<div class="lbl">입력 중<em>전송 활성</em></div>{cmp("on", "덜 맵게 해 줘")}
<div class="lbl">생성 중 · 빈 입력<em>멈춤</em></div>{cmp("gen", "", "지금 보내면 이 답을 멈추고 새 말과 함께 답해요")}
<div class="lbl">생성 중 · 입력 있음<em>전송 = 멈추고 함께 보내기</em></div>{cmp("on", "4인분으로", "보내면 지금 답을 멈추고 위 질문과 함께 답해요")}
<div class="lbl">+ 버튼 → 이번만 조건<em>잠긴 기준은 끌 수 없음</em></div>
<div style="display:flex;gap:6px;flex-wrap:wrap">{lockchip("갑각류")}{lockchip("땅콩")}{lockchip("우유")}<span class="tg2 tf" style="height:28px;border-radius:999px;padding:0 10px">덜 맵게 {ic("x", 11, 2.6)}</span><span class="tg2 tf" style="height:28px;border-radius:999px;padding:0 10px">15분 안에 {ic("x", 11, 2.6)}</span></div>
</div>'''

# 3 메시지 상태 -----------------------------------------------------------------
c3 = f'''<div class="card"><h3><i>03</i>메시지 상태</h3><div class="sub">보낸 말이 어떻게 처리됐는지 말풍선 아래에 짧게 남겨요.</div>
<div class="lbl">보냄</div><div class="me" style="font-size:14px">버터 떡볶이 만들어 줘</div><div class="meta">오후 6:12</div>
<div class="lbl">멈춤</div><div class="me" style="font-size:14px;opacity:.75">버터 떡볶이 만들어 줘</div><div style="display:flex;justify-content:flex-end;margin-top:5px"><span class="tg2 tz">{ic("stop", 10)}멈춤 · 다음 메시지와 함께 답해요</span></div>
<div class="lbl">함께 보냄</div><div class="me" style="font-size:14px">4인분으로</div><div class="meta" style="display:flex;gap:4px;justify-content:flex-end;align-items:center">{ic("link", 11, 2.4)}위 질문과 함께 보냄</div>
<div class="lbl">실패</div>
<div style="display:flex;gap:10px;align-items:center;background:#FBEAE7;border:1px solid #F1CBC4;border-radius:14px;padding:10px 12px">{ic("err", 18, 2.2, "color:#B83224")}<div style="flex:1;font-size:13px;line-height:1.45"><b>답을 만들지 못했어요</b><div style="color:var(--ink2);font-size:12px">연결이 끊겼거나 시간이 오래 걸렸어요</div></div><div class="btn b-line" style="height:34px;font-size:12.5px;padding:0 12px">{ic("retry", 13, 2.6)}다시 시도</div></div>
</div>'''

# 4 재료 꼬리표 -----------------------------------------------------------------
c4 = f'''<div class="card"><h3><i>04</i>재료 꼬리표</h3><div class="sub">재료마다 하나만 붙여요. 아무것도 없으면 "검사했고 찾지 못함"이지 "안전"이 아닙니다.</div>
<div style="display:grid;grid-template-columns:auto 1fr;gap:10px 14px;align-items:center;margin-top:14px;font-size:12.5px;color:var(--ink2)">
{tag("r", "갑각류")}<span>등록한 알레르기와 겹침 · 줄 전체를 붉게 강조</span>
{tag("y", "원재료 확인")}<span>시판품 · 못 읽은 재료 · 판정 불가</span>
{tag("f", "버터 대신")}<span>내 기준 때문에 바꾼 재료</span>
<span class="tg2 tz">새우</span><span>비회원 · 판정 없이 이름만</span></div>
<div class="lbl" style="margin-top:16px">재료 줄</div>
<div style="background:var(--paper);border-radius:14px;padding:2px 14px">
<div class="ingr" style="font-size:13.5px"><div class="n">떡볶이 떡{tag("y", "원재료 확인")}</div><div class="q">300g</div></div>
<div class="ingr hit" style="font-size:13.5px"><div class="n">어묵{tag("r", "갑각류")}</div><div class="q">2장</div></div>
<div class="ingr" style="font-size:13.5px;border:0"><div class="n">대파</div><div class="q">1대</div></div></div>
</div>'''

# 5 행동 버튼 -----------------------------------------------------------------
def g(i, t, extra=''):
    return f'<div class="ghost" style="{extra}">{ic(i, 14)}{t}</div>'


c5 = f'''<div class="card"><h3><i>05</i>행동 버튼 · 로그인 상태별</h3><div class="sub">지금 앱에 있는 기능 그대로입니다. 웹은 복사, 앱은 공유.</div>
<div class="lbl">회원</div><div style="display:flex;gap:6px;flex-wrap:wrap">{g("heart", "저장")}{g("cal", "식단에 추가")}{g("copy", "복사")}{g("vol", "듣기")}</div>
<div class="lbl">저장한 뒤</div><div style="display:flex;gap:6px;flex-wrap:wrap">{g("heartf", "저장됨", "color:var(--terraD);border-color:#F1C9BB")}<div class="toast" style="padding:8px 12px;font-size:12.5px">{ic("check", 14, 2.8, "color:var(--mint)")}내 레시피에 저장했어요<a>보기</a></div></div>
<div class="lbl">저장할 수 없는 레시피<em>카드 ID 없음</em></div><div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">{g("heart", "저장", "opacity:.45")}<span style="font-size:12px;color:var(--ink2)">이 답은 레시피 카드가 아니라 저장할 수 없어요</span></div>
<div class="lbl">비회원</div><div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">{g("copy", "복사")}<span style="font-size:12px;color:var(--ink2)">저장 · 식단 · 듣기는 <b style="color:var(--terra)">로그인</b> 후</span></div>
</div>'''

# 6 횟수 · 소진 ----------------------------------------------------------------
c6 = f'''<div class="card"><h3><i>06</i>새 요리 찾기 횟수</h3><div class="sub">웹 검색이 필요한 "처음 보는 요리"만 셉니다. 회원 하루 5번, 비회원 3번, PLUS는 제한 없음.</div>
<div class="lbl">남은 횟수</div>
<div style="display:flex;gap:8px;flex-wrap:wrap"><div class="ghost" style="color:var(--ink2)">{ic("spark", 13, 2.2)}오늘 4번 남음 <span style="display:flex;gap:3px">{"".join(f'<i style="width:7px;height:7px;border-radius:50%;background:{c};display:block"></i>' for c in ["var(--terra)"]*4+["var(--line)"])}</span></div><div class="ghost" style="color:var(--terraD);border-color:#F1C9BB">{ic("spark", 13, 2.2)}오늘 1번 남음</div></div>
<div class="lbl">다 썼을 때 · 서버 안내 문구 그대로</div>
<div style="display:flex;gap:10px;background:var(--paper);border:1px solid var(--line);border-radius:16px;padding:12px 14px"><div class="av" style="width:26px;height:26px;border-radius:9px">{logo(14)}</div>
<div style="font-size:13px;line-height:1.55">오늘 새 요리 찾기(하루 5회)를 모두 사용했어요. 이미 찾아 둔 요리와 저장된 레시피는 계속 물어볼 수 있고, 새 요리 찾기는 내일 다시 할 수 있어요.
<div style="display:flex;gap:6px;margin-top:10px"><div class="btn b-forest" style="height:34px;font-size:12.5px;padding:0 13px">내 레시피 보기</div><div class="btn b-line" style="height:34px;font-size:12.5px;padding:0 13px">PLUS 알아보기 · 웹</div></div></div></div>
</div>'''

# 7 토큰 ---------------------------------------------------------------------
sw = [('--forest', '#0B3B32', '숲 · 기본'), ('--cream', '#F6F0E4', '크림 · 바탕'), ('--paper', '#FFFBF3', '종이 · 카드'), ('--terra', '#C2502F', '테라 · 행동'),
      ('--ink', '#10201B', '잉크 · 글자'), ('red', '#B83224', '충돌'), ('yel', '#9A6410', '확인 필요'), ('gry', '#6B716D', '대상 없음'), ('grn', '#2F7D4F', '찾지 못함')]
sws = ''.join(f'<div><b style="background:{h}"></b><span>{n}</span><small>{h}</small></div>' for _, h, n in sw)
c7 = f'''<div class="card" style="grid-column:span 2"><h3><i>07</i>토큰 · 글자 · 모서리</h3><div class="sub">배너 4색은 행동 색(테라)과 겹치지 않게 따로 둡니다. 글자 대비는 모두 4.5:1 이상으로 맞췄습니다(배너 라벨 5.1~5.6, 보조 글자 4.8, 테라 버튼 4.7).</div>
<div style="display:flex;gap:30px;margin-top:4px">
<div style="flex:1.2"><div class="sw">{sws}</div></div>
<div style="flex:1">
<div class="tyrow"><small>표제 66/900</small><span style="font-size:30px;font-weight:900;letter-spacing:-1px">오늘 뭐 먹고 싶어요?</span></div>
<div class="tyrow"><small>제목 18/900</small><span style="font-size:18px;font-weight:900">버터 떡볶이 (우유 제외)</span></div>
<div class="tyrow"><small>본문 15/400</small><span style="font-size:15px">웹 레시피 3곳을 바탕으로 정리했어요.</span></div>
<div class="tyrow"><small>배너 15/800</small><span style="font-size:15px;font-weight:800">어묵 — 등록한 갑각류와 겹쳐요</span></div>
<div class="tyrow" style="border:0"><small>보조 12/700</small><span style="font-size:12px;font-weight:700;color:var(--ink2)">출처 3곳 · 재료 9개 · 31초</span></div>
<div style="display:flex;gap:10px;margin-top:12px;font-size:11px;color:var(--ink3);font-weight:800">
<div style="text-align:center"><div style="width:44px;height:30px;border:2px solid var(--forest);border-radius:7px"></div>꼬리표 7</div>
<div style="text-align:center"><div style="width:44px;height:30px;border:2px solid var(--forest);border-radius:14px"></div>배너 16</div>
<div style="text-align:center"><div style="width:44px;height:30px;border:2px solid var(--forest);border-radius:20px 20px 6px 20px"></div>말풍선</div>
<div style="text-align:center"><div style="width:44px;height:30px;border:2px solid var(--forest);border-radius:15px"></div>버튼 999</div></div>
</div></div></div>'''

html = (f'<!doctype html><html lang="ko"><head><meta charset="utf-8"><link rel="stylesheet" href="key.css"><style>{CSS}{SCSS}</style></head><body>'
        f'<div class="board"><div class="hd"><b>명세</b><span>상태와 컴포넌트</span><em>개발자가 화면을 만들 때 그대로 옮길 수 있도록 상태별 모습과 문구를 정리했습니다.</em></div>'
        f'<div class="grid">{c1}{c2}{c3}{c4}{c5}{c6}{c7}</div></div></body></html>')
(OUT / 'cf-spec.html').write_text(html, encoding='utf-8')
print('ok')
