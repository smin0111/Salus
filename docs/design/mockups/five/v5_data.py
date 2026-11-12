# 다섯 방향 시안이 함께 쓰는 예시 데이터와 렌더러.
# 수치는 서버가 실제로 주는 값(인분, 조리 시간, 재료, 단계, 알레르기 검사 결과)만 쓴다.
# 진행률, 남은 횟수, 처리 시간처럼 서버가 주지 않는 값은 넣지 않는다.
import os, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

USER = '승민'
ALLERGIES = ['우유', '새우']
HEALTH_TAGS = ['고혈압']

Q = '로제떡볶이 2인분 만들고 싶어요. 너무 맵지 않게요.'
INTRO = ('등록된 우유 알레르기에 맞춰 우유와 생크림은 귀리음료로 바꾸고 치즈는 뺐어요. '
         '매운맛은 고춧가루를 절반으로 줄여서 맞췄어요.')

TITLE = '귀리음료 로제떡볶이'
SERVES, TIME, NOTE = '2인분', '25분 안팎', '덜 맵게'

# (알레르기, 상태, 설명). 상태는 conflict / check / none 중 하나.
CHECKS = [
    ('우유', 'check', '우유와 생크림 대신 귀리음료를 넣었어요. 귀리음료는 제품에 따라 유성분 혼입 가능 표시가 있어요. 포장의 원재료를 확인해 주세요.'),
    ('새우', 'check', '레시피 재료에는 없어요. 다만 어묵은 제품에 따라 새우가 들어가요. 어묵 원재료를 확인해 주세요.'),
]
STATUS = {'conflict': '겹침', 'check': '확인 필요', 'none': '재료 이름 기준 겹침 없음'}

HEALTH = ('고혈압', '고추장과 어묵 때문에 나트륨이 많은 편이에요. 고추장을 1큰술로 줄이면 덜 짜요.')

# (재료, 양, 메모)
INGR = [
    ('떡볶이 떡', '300g', ''),
    ('어묵', '2장', '원재료 확인'),
    ('양파', '1/2개', ''),
    ('대파', '1/2대', ''),
    ('귀리음료(무가당)', '300ml', '우유·생크림 대신, 원재료 확인'),
    ('물', '150ml', ''),
    ('고추장', '1½큰술', ''),
    ('고춧가루', '1/2큰술', '원래 1큰술'),
    ('설탕', '1큰술', ''),
    ('다진 마늘', '1작은술', ''),
    ('식용유', '1큰술', ''),
]

STEPS = [
    '떡은 찬물에 10분 담가 두세요. 어묵은 한입 크기로 자르고, 양파는 채 썰고, 대파는 어슷 썰어요.',
    '팬에 식용유를 두르고 다진 마늘과 양파를 중불에서 2분 볶아요.',
    '고추장, 고춧가루, 설탕을 넣고 30초 더 볶아 양념의 날맛을 없애요.',
    '물과 귀리음료를 붓고, 끓어오르면 떡과 어묵을 넣어 약불에서 6~7분 저어 가며 졸여요.',
    '소스가 걸쭉해지면 대파를 넣고 1분 더 끓여 마무리해요.',
]
TIP = '귀리음료는 센불에서 오래 끓이면 분리될 수 있어요. 넣은 뒤에는 약불을 유지하세요.'

CHANGES = [
    ('우유 200ml', '귀리음료로'),
    ('생크림 100ml', '귀리음료로'),
    ('모차렐라 치즈', '뺌'),
    ('고춧가루 1큰술', '1/2큰술로'),
]

SOURCES = [
    ('레시피 사이트', '로제떡볶이 기본 레시피', 'recipe.example.kr (출처 표시 예시)'),
    ('개인 블로그', '우유 없이 만든 로제소스 후기', 'blog.example.com (출처 표시 예시)'),
]

DISCLAIMER = 'Salus는 의료 조언을 하지 않아요. 알레르기는 실제 제품 포장의 원재료 표시로 마지막 확인을 해 주세요.'

CONVOS = [
    ('로제떡볶이 2인분', '오늘'),
    ('마라샹궈, 땅콩 없이', '어제'),
    ('닭가슴살 샐러드 소스', '10월 26일'),
    ('두부조림 간장 줄이기', '10월 24일'),
    ('들기름 막국수', '10월 20일'),
    ('김치볶음밥 1인분', '10월 12일'),
]

# (제목, 인분, 시간, 상태 문구, 상태 종류, 날짜)
SAVED = [
    ('귀리음료 로제떡볶이', '2인분', '25분', '확인 필요 2', 'check', '오늘'),
    ('땅콩 뺀 마라샹궈', '1인분', '30분', '확인 필요 3', 'check', '10월 29일'),
    ('저염 두부조림', '2인분', '20분', '재료 이름 기준 겹침 없음', 'none', '10월 24일'),
    ('들기름 막국수', '1인분', '15분', '확인 필요 1', 'check', '10월 20일'),
    ('닭가슴살 샐러드', '1인분', '10분', '재료 이름 기준 겹침 없음', 'none', '10월 18일'),
    ('김치볶음밥', '1인분', '15분', '검사 기록 없음', 'unknown', '9월 30일'),
]


def write(name, html):
    path = os.path.join(HERE, name + '.html')
    with open(path, 'w') as f:
        f.write(html)
    return path


def render(name, html, w, h):
    path = write(name, html)
    out = os.path.join(HERE, name + '.png')
    subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars',
                    f'--window-size={w},{h}', '--force-device-scale-factor=1.5',
                    '--virtual-time-budget=6000', f'--screenshot={out}', 'file://' + path],
                   check=True, capture_output=True)
    return out


BOARD_CSS = '''
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:#D9D6CF}
.bd{padding:36px 40px 40px;font-family:Pretendard,'Apple SD Gothic Neo',sans-serif;color:#1d1d1b}
.bd>.h{display:flex;align-items:baseline;gap:16px;margin-bottom:22px}
.bd>.h b{font-size:22px;font-weight:800;letter-spacing:-.3px}
.bd>.h span{font-size:15px;color:#55554f}
.phones{display:flex;gap:40px}
.ph{width:390px;height:844px;border-radius:44px;overflow:hidden;position:relative;outline:9px solid #1c1c1b}
.ph .sb{height:50px;display:flex;justify-content:space-between;align-items:center;padding:0 30px 0 34px;font:600 15px Pretendard,sans-serif}
.web{width:1440px;height:900px;overflow:hidden;position:relative;border-radius:10px;box-shadow:0 0 0 1px rgba(0,0,0,.12)}
'''
PRETENDARD = "@import url('https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css');"


def page(css, body, fonts=''):
    # data-scroll="end"인 결과 화면은 끝까지 스크롤된 상태로 그린다(같은 화면의 아래쪽).
    js = '<script>addEventListener("load",()=>setTimeout(()=>document.querySelectorAll("[data-scroll=end]").forEach(e=>e.scrollTop=e.scrollHeight),300))</script>'
    return (f'<!doctype html><html lang="ko"><head><meta charset="utf-8"><style>{PRETENDARD}{fonts}'
            f'{BOARD_CSS}{css}</style></head><body>{body}{js}</body></html>')


def web_board(key, label, concept, css, frame, fonts=''):
    body = f'<div class="bd"><div class="h"><b>{label}</b><span>{concept}</span></div><div class="web">{frame}</div></div>'
    return render(f'v5-{key}-web', page(css, body, fonts), 1520, 1018)


def app_board(key, label, concept, css, phones, fonts=''):
    inner = ''.join(f'<div class="ph">{p}</div>' for p in phones)
    body = f'<div class="bd"><div class="h"><b>{label}</b><span>{concept}</span></div><div class="phones">{inner}</div></div>'
    return render(f'v5-{key}-app', page(css, body, fonts), 80 + 390 * len(phones) + 40 * (len(phones) - 1), 980)
