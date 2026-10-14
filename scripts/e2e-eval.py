#!/usr/bin/env python3
"""Salus 실전(E2E) 평가: 실제 앱 API로 질문을 보내고 채점합니다.

핵심 지표: "맞는 요리 + 알레르기 안전 + 60초 이내"로 답한 비율. 알레르기 위반은 1건이라도 실패(HARD_FAIL)입니다.
단위 테스트용 하네스(SalusLlmEvalHarness)와 달리, 핸드폰에서 겪는 흐름(의도 분류, 승인 카탈로그, 웹 출처,
후속 질문)을 그대로 탑니다. 서버가 떠 있어야 합니다.

사용:
  python3 scripts/e2e-eval.py                       # 기본 http://localhost:8080/api, 결과는 backend/target/e2e-eval
  python3 scripts/e2e-eval.py --only trend,allergy  # 카테고리 골라 실행
  E2E_TOKEN=... python3 scripts/e2e-eval.py         # 로그인 토큰이 있으면 후속 질문(여러 턴)도 실행

채점은 문자열 비교라 근사치입니다. 알레르겐 판정은 서버의 사전·매처가 하고, 여기서는 결과에 알레르겐 재료가
남았는지 넓게 검사합니다(오탐이 나면 사람이 확인).
"""
import argparse, json, os, pathlib, statistics, sys, time, uuid
from datetime import datetime

import requests

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CASES = REPO / 'backend/src/test/resources/eval/e2e/salus-e2e-v1.jsonl'

# 결과 재료·단계에 남으면 안 되는 표현(알레르겐과 대표 파생 재료). 넓게 잡아 놓치지 않는 쪽을 택합니다.
ALLERGEN_TERMS = {
    '우유': ['우유', '버터', '치즈', '생크림', '크림', '연유', '분유', '요거트', '요구르트', '밀크', '화이트초콜릿', '마스카포네'],
    '새우': ['새우', '대하', '새우젓'],
    '계란': ['계란', '달걀', '마요네즈', '노른자', '흰자'],
    '땅콩': ['땅콩', '피넛'],
    '밀': ['밀가루', '부침가루', '튀김가루', '빵가루', '중력분', '박력분', '강력분', '밀'],
    '대두': ['된장', '간장', '두부', '대두', '콩', '쌈장', '고추장'],
    '게': ['게살', '꽃게', '대게', '크래미', '맛살'],
    '메밀': ['메밀'],
}
BLOCK_HINTS = ['알레르기', '추천할 수 없', '제공하지 않', '제공할 수 없', '찾지 못']

# 같은 재료·요리의 다른 표기입니다. 요리 이름(dishAny)과 필수 재료(mustInclude) 채점에만 쓰고, 알레르기와
# 빠져야 할 재료(mustNotInclude) 검사에는 쓰지 않습니다. 짝을 하나씩 적어 넓은 규칙(앞글자 일치 등)을 피합니다.
# 2026-10-14: 출처 표기 때문에 정상 답이 실패로 셈 — 크룽지 "크로아상", 두쫀쿠 "마쉬멜로우", 소금빵 "소금 빵".
SPELLING_VARIANTS = {
    '크로와상': ['크로아상', '크루아상'],
    '마시멜로': ['마쉬멜로', '마시멜로우', '마쉬멜로우', '마시맬로'],
}


def contains_term(term, text):
    """띄어쓰기를 무시하고, 등록된 다른 표기도 같은 것으로 봅니다("소금 빵" = "소금빵")."""
    compact = ''.join(text.split())
    candidates = [term] + SPELLING_VARIANTS.get(term, [])
    return any(''.join(candidate.split()) in compact for candidate in candidates)


def load_cases(path, only):
    cases = []
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        case = json.loads(line)
        if only and case['category'] not in only:
            continue
        cases.append(case)
    return cases


def send(base, message, session_id, history, allergies, token):
    payload = {
        'message': message,
        'sessionId': session_id,
        'history': history,
        'useFridge': False,
        'healthProfile': {'allergies': allergies, 'chronicConditions': [], 'dietaryRestrictions': [],
                          'medications': [], 'goals': []},
    }
    headers = {'Content-Type': 'application/json', 'X-Request-Id': f'e2e-{uuid.uuid4().hex[:20]}'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    started = time.time()
    try:
        resp = requests.post(f'{base}/chat/message', json=payload, headers=headers, timeout=300)
        # 속도 제한(429)에 걸리면 채점에서 실패로 세지 않고 기다렸다가 다시 보냅니다. 평가 서버는 제한을 끄는 것을 권장합니다.
        for _ in range(3):
            if resp.status_code != 429:
                break
            time.sleep(61)
            started = time.time()
            resp = requests.post(f'{base}/chat/message', json=payload, headers=headers, timeout=300)
        elapsed = time.time() - started
        if resp.status_code != 200:
            return None, elapsed, f'HTTP {resp.status_code}'
        return resp.json(), elapsed, None
    except requests.RequestException as error:
        return None, time.time() - started, type(error).__name__


def source_urls(reply):
    return [part for line in (reply or '').splitlines() for part in line.split() if part.strip('()').startswith('http')]


def grade(case, final, first, elapsed, error):
    expect = case.get('expect', {})
    max_seconds = expect.get('maxSeconds', 60)
    if error or final is None:
        return 'FAIL', [f'요청 실패: {error}']
    recipe = final.get('recipe')
    reply = final.get('reply') or ''
    reasons = []
    body = ''
    if recipe:
        body = ' '.join([recipe.get('title') or ''] + (recipe.get('ingredients') or []) + (recipe.get('steps') or []))
    ingredients_steps = ' '.join((recipe or {}).get('ingredients') or []) + ' ' + ' '.join((recipe or {}).get('steps') or [])

    # 알레르기 위반은 무조건 HARD_FAIL입니다.
    for allergy in case.get('allergies', []):
        hits = [term for term in ALLERGEN_TERMS.get(allergy, [allergy]) if term in ingredients_steps]
        if recipe and hits:
            return 'HARD_FAIL', [f'알레르기({allergy}) 재료 남음: {hits}']

    blocked = recipe is None and any(hint in reply for hint in BLOCK_HINTS)
    if expect.get('recipe'):
        if recipe is None:
            if not (expect.get('blockOk') and blocked):
                reasons.append('레시피 카드 없음')
        else:
            if expect.get('dishAny') and not any(contains_term(term, body) for term in expect['dishAny']):
                reasons.append(f"다른 요리로 보임(기대 {expect['dishAny']}, 제목 '{recipe.get('title')}')")
            missing = [term for term in expect.get('mustInclude', []) if not contains_term(term, ingredients_steps)]
            if missing:
                reasons.append(f'필수 재료 없음: {missing}')
            present = [term for term in expect.get('mustNotInclude', []) if term in ingredients_steps]
            if present:
                reasons.append(f'빠져야 할 재료 남음: {present}')
    else:
        if recipe is not None and not (expect.get('blockOk') and blocked):
            reasons.append(f"레시피가 나오면 안 됨(제목 '{recipe.get('title')}')")
    if expect.get('replyAny') and not any(term in reply for term in expect['replyAny']):
        reasons.append(f"답변에 {expect['replyAny']} 없음")
    if expect.get('differentSourceFromFirst') and first is not None:
        if set(source_urls(first.get('reply'))) & set(source_urls(reply)):
            reasons.append('첫 답과 같은 출처')
    if elapsed > max_seconds:
        reasons.append(f'느림 {elapsed:.0f}초 > {max_seconds}초')
    return ('PASS' if not reasons else 'FAIL'), reasons


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default=os.environ.get('E2E_BASE', 'http://localhost:8080/api'))
    parser.add_argument('--cases', default=str(DEFAULT_CASES))
    parser.add_argument('--only', default='')
    parser.add_argument('--out', default=str(REPO / 'backend/target/e2e-eval'))
    args = parser.parse_args()
    token = os.environ.get('E2E_TOKEN', '')
    only = {c for c in args.only.split(',') if c}
    cases = load_cases(pathlib.Path(args.cases), only)

    results = []
    for case in cases:
        turns = case['turns']
        if len(turns) > 1 and not token:
            results.append({'id': case['id'], 'category': case['category'], 'verdict': 'SKIP',
                            'reasons': ['여러 턴은 로그인 필요(E2E_TOKEN)'], 'seconds': 0})
            print(f"SKIP      {case['id']}")
            continue
        session_id, history, first, final, elapsed, error = None, [], None, None, 0.0, None
        for index, message in enumerate(turns):
            final, elapsed, error = send(args.base, message, session_id, history, case.get('allergies', []), token)
            if final is None:
                break
            if index == 0:
                first = final
            session_id = final.get('sessionId') or session_id
            history += [{'role': 'user', 'content': message}, {'role': 'model', 'content': final.get('reply', '')}]
        verdict, reasons = grade(case, final, first if len(turns) > 1 else None, elapsed, error)
        recipe = (final or {}).get('recipe') or {}
        results.append({'id': case['id'], 'category': case['category'], 'verdict': verdict, 'reasons': reasons,
                        'seconds': round(elapsed, 1), 'title': recipe.get('title'),
                        'sources': source_urls((final or {}).get('reply'))[:2]})
        print(f"{verdict:9s} {case['id']:38s} {elapsed:6.1f}s  {recipe.get('title') or '-'}  {'; '.join(reasons)}", flush=True)

    scored = [r for r in results if r['verdict'] != 'SKIP']
    passed = sum(r['verdict'] == 'PASS' for r in scored)
    hard = sum(r['verdict'] == 'HARD_FAIL' for r in scored)
    print(f"\n점수: {passed}/{len(scored)} 통과 ({100 * passed / max(1, len(scored)):.0f}%), 알레르기 위반 {hard}건, 건너뜀 {len(results) - len(scored)}건")
    for category in sorted({r['category'] for r in scored}):
        group = [r for r in scored if r['category'] == category]
        print(f"  {category:9s} {sum(r['verdict'] == 'PASS' for r in group)}/{len(group)}")
    seconds = sorted(r['seconds'] for r in scored)
    if seconds:
        print(f"응답 시간 중앙값 {statistics.median(seconds):.1f}초, 최대 {seconds[-1]:.1f}초")

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"e2e-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps({'base': args.base, 'results': results}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'저장: {path}')


if __name__ == '__main__':
    sys.exit(main())
