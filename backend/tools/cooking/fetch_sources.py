"""평가 근거 원문을 내려받아 cache/<id>.txt(본문 텍스트)로 저장합니다. cache/는 저장소에 넣지 않습니다.

실행: python3 tools/cooking/fetch_sources.py
원문 라이선스는 페이지마다 다릅니다(공공누리 제1유형·제4유형, 전재 기사, 학술 초록). 이 파일들은 평가 채점 근거로만 씁니다.
"""
import html
import json
import re
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
CACHE = HERE / "cache"
STOP = re.compile(r"^(이전기사|\[문의\]|문의 ?:|정책브리핑 게시물|본 저작물은|\(54875\)|<자료=|“이 자료는|이 자료는 텍스트에 한하여)")
SKIP = re.compile(r"누리집|바로가기|뉴스레터|조직도|운영‧관리|\.hwpx?|\.pdf \[|클릭하시면|태극기|영상정보처리|부처별 뉴스|무단 전재")
LICENSE = re.compile(r"공공누리\"?\s*제\d유형")


def text_lines(raw):
    for encoding in ("utf-8", "euc-kr", "cp949"):
        try:
            page = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    page = re.sub(r"(?is)<(script|style|noscript)[^>]*>.*?</\1>", " ", page)
    page = re.sub(r"(?i)<br\s*/?>|</p>|</li>|</div>|</h\d>|</tr>", "\n", page)
    page = html.unescape(re.sub(r"<[^>]+>", " ", page))
    lines = [re.sub(r"[ \t ]+", " ", line).strip() for line in page.split("\n")]
    return [line for line in lines if len(line) >= 15 and re.search(r"[가-힣]", line)]


def body(lines):
    kept, started = [], False
    for line in lines[1:]:
        if STOP.search(line):
            if started:
                break
            continue
        if SKIP.search(line):
            continue
        if len(line) >= 25:
            started = True
        if started:
            kept.append(line)
    return kept


def main():
    CACHE.mkdir(exist_ok=True)
    sources = json.loads((HERE / "sources.json").read_text(encoding="utf-8"))
    for source in sources:
        request = urllib.request.Request(source["url"], headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            lines = text_lines(response.read())
        license_match = next((m.group(0) for line in lines for m in [LICENSE.search(line)] if m), "표시 없음")
        text = "\n".join([lines[0] if lines else "", *body(lines)])
        (CACHE / f'{source["id"]}.txt').write_text(text, encoding="utf-8")
        (CACHE / f'{source["id"]}.license').write_text(license_match, encoding="utf-8")
        print(f'{source["id"]:<16} {len(text):>6}자 {license_match}')


if __name__ == "__main__":
    main()
