#!/usr/bin/env python3
"""DB에 없는 요리의 레시피를 웹에서 어디까지 확보할 수 있는지 조사합니다.

요리마다 SearXNG로 검색하고 상위 페이지를 가져와서, 근거 등급을 셉니다.
  STRUCTURED   schema.org Recipe JSON-LD가 있고 재료 3개 이상, 단계 2개 이상 (LLM 없이 추출 가능)
  TEXT_RECIPE  구조화는 없지만 본문에 재료 표기와 분량 표현이 5개 이상 (LLM 추출 대상)
  THIN         페이지는 받았지만 레시피로 쓸 내용이 부족
  FAIL         받지 못함(차단, 오류, HTML 아님)
유튜브 결과는 따로 셉니다. 가져온 HTML은 이후 결정적 평가용 스냅숏으로 저장합니다.
스냅숏은 제3자 저작물이므로 로컬에만 두고 저장소에 커밋하지 않습니다.

사용: python3 scripts/longtail-source-probe.py [출력 디렉터리] [요리 ...]
"""
import hashlib, html, json, pathlib, re, sys, time, urllib.parse
from datetime import datetime

import requests

SEARXNG = 'http://localhost:8888/search'
# 앱의 SearxngSearchEngine과 같은 꼬리말·엔진·언어를 씁니다.
QUERY_SUFFIX = ' 레시피 재료 분량 조리 순서'
ENGINES = 'google,bing,duckduckgo'
# 앱의 DefaultSafeWebPageFetcher와 같은 User-Agent로 실제 조건을 재현합니다.
USER_AGENT = 'SalusRecipeAgent/1.0'
PAGES_PER_DISH = 5
DEFAULT_DISHES = [
    '두바이 쫀득 쿠키', '두쫀쿠', '달고나 커피', '쫀득 쿠키', '쇠고기무국', '소고기무국',
    '탕후루', '마라샹궈', '로제떡볶이', '크룽지', '오이김밥', '두바이 초콜릿', '버터떡', '요거트 아이스크림',
]
QTY = re.compile(r'\d+(?:[./]\d+)?\s*(?:g|kg|ml|mL|L|큰술|작은술|스푼|숟가락|컵|개|알|장|T\b|t\b|티스푼|꼬집|줌)')
LD_JSON = re.compile(r'<script[^>]*type=["\']application/ld\+json["\'][^>]*>(.*?)</script>', re.S | re.I)


def find_recipes(node):
    if isinstance(node, list):
        for item in node:
            yield from find_recipes(item)
    elif isinstance(node, dict):
        types = node.get('@type')
        types = types if isinstance(types, list) else [types]
        if 'Recipe' in types:
            yield node
        for key in ('@graph', 'mainEntity', 'itemListElement'):
            if key in node:
                yield from find_recipes(node[key])


def count_steps(instructions):
    if isinstance(instructions, str):
        return len([s for s in re.split(r'[\n.]', instructions) if s.strip()])
    if isinstance(instructions, list):
        total = 0
        for step in instructions:
            if isinstance(step, dict) and step.get('@type') == 'HowToSection':
                total += count_steps(step.get('itemListElement', []))
            else:
                total += 1
        return total
    return 0


def page_text(raw_html):
    body = re.sub(r'<(script|style|noscript)[^>]*>.*?</\1>', ' ', raw_html, flags=re.S | re.I)
    body = re.sub(r'<[^>]+>', ' ', body)
    return re.sub(r'\s+', ' ', html.unescape(body)).strip()


def classify(raw_html):
    recipes = []
    for block in LD_JSON.findall(raw_html):
        try:
            recipes.extend(find_recipes(json.loads(block.strip())))
        except (json.JSONDecodeError, ValueError):
            continue
    best = None
    for recipe in recipes:
        ingredients = recipe.get('recipeIngredient') or recipe.get('ingredients') or []
        steps = count_steps(recipe.get('recipeInstructions'))
        cand = {'ingredients': len(ingredients) if isinstance(ingredients, list) else 0, 'steps': steps}
        if best is None or cand['ingredients'] > best['ingredients']:
            best = cand
    text = page_text(raw_html)
    qty = len(QTY.findall(text))
    info = {'jsonld_recipe': best, 'text_chars': len(text), 'qty_mentions': qty, 'has_jaeryo': '재료' in text}
    if best and best['ingredients'] >= 3 and best['steps'] >= 2:
        return 'STRUCTURED', info
    if qty >= 5 and info['has_jaeryo']:
        return 'TEXT_RECIPE', info
    return 'THIN', info


def fetch(url):
    try:
        resp = requests.get(url, headers={'User-Agent': USER_AGENT}, timeout=12, allow_redirects=True)
    except requests.RequestException as error:
        return None, f'ERROR {type(error).__name__}'
    ctype = resp.headers.get('content-type', '')
    if resp.status_code != 200:
        return None, f'HTTP {resp.status_code}'
    if 'html' not in ctype:
        return None, f'NOT_HTML {ctype[:30]}'
    resp.encoding = resp.encoding if resp.encoding and resp.encoding.lower() != 'iso-8859-1' else resp.apparent_encoding
    return resp.text, 'OK'


def mobile_naver(url):
    parsed = urllib.parse.urlparse(url)
    if parsed.netloc == 'blog.naver.com':
        return urllib.parse.urlunparse(parsed._replace(netloc='m.blog.naver.com'))
    return None


def probe(dish, out_dir):
    params = {'q': dish + QUERY_SUFFIX, 'format': 'json', 'language': 'ko', 'engines': ENGINES, 'safesearch': 1}
    try:
        data = requests.get(SEARXNG, params=params, timeout=20).json()
    except (requests.RequestException, ValueError) as error:
        return {'dish': dish, 'search': f'FAILED {type(error).__name__}', 'pages': [], 'tier': 'SEARCH_FAILED'}
    results = data.get('results') or []
    youtube = [r['url'] for r in results if 'youtube.com' in r.get('url', '') or 'youtu.be' in r.get('url', '')]
    pages = []
    for result in [r for r in results if r not in youtube and 'youtu' not in r.get('url', '')][:PAGES_PER_DISH]:
        url = result.get('url', '')
        raw, status = fetch(url)
        entry = {'url': url, 'host': urllib.parse.urlparse(url).netloc, 'status': status}
        if raw is not None:
            entry['tier'], entry['info'] = classify(raw)
            name = hashlib.sha1(url.encode()).hexdigest()[:12]
            (out_dir / f'{name}.html').write_text(raw, encoding='utf-8')
            entry['snapshot'] = f'{name}.html'
        else:
            entry['tier'] = 'FAIL'
        mobile = mobile_naver(url)
        if mobile:
            raw_m, status_m = fetch(mobile)
            entry['mobile_status'] = status_m
            if raw_m is not None:
                entry['mobile_tier'], entry['mobile_info'] = classify(raw_m)
                name = hashlib.sha1(mobile.encode()).hexdigest()[:12]
                (out_dir / f'{name}.html').write_text(raw_m, encoding='utf-8')
                entry['mobile_snapshot'] = f'{name}.html'
        pages.append(entry)
        time.sleep(1)
    order = ['STRUCTURED', 'TEXT_RECIPE', 'THIN', 'FAIL']
    tiers = [p['tier'] for p in pages] + [p['mobile_tier'] for p in pages if 'mobile_tier' in p]
    best = min(tiers, key=order.index) if tiers else 'NO_RESULTS'
    best_without_naver_fix = min([p['tier'] for p in pages], key=order.index) if pages else 'NO_RESULTS'
    return {
        'dish': dish, 'search': 'OK', 'result_count': len(results), 'youtube_count': len(youtube),
        'unresponsive_engines': data.get('unresponsive_engines') or [],
        'tier': best, 'tier_app_as_is': best_without_naver_fix, 'pages': pages,
    }


def main():
    root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path('longtail-probe')
    dishes = sys.argv[2:] or DEFAULT_DISHES
    out_dir = root / datetime.now().strftime('%Y%m%d-%H%M%S')
    out_dir.mkdir(parents=True, exist_ok=True)
    report = []
    for dish in dishes:
        row = probe(dish, out_dir)
        report.append(row)
        hosts = ','.join(sorted({p['host'].replace('www.', '')[:18] for p in row['pages']}))
        print(f"{dish:12s} 최고등급={row['tier']:12s} 앱그대로={row.get('tier_app_as_is', '-'):12s} "
              f"결과={row.get('result_count', 0):>2} 유튜브={row.get('youtube_count', 0):>2} "
              f"응답없는엔진={len(row.get('unresponsive_engines', []))} 출처={hosts}", flush=True)
    (out_dir / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'\n저장: {out_dir}')


if __name__ == '__main__':
    main()
