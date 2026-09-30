#!/usr/bin/env python3
"""실행 간 변동 폭을 계산합니다.

같은 코드로 돌린 실행을 모두 모아 케이스별 통과 횟수와 실행별 통과 수를 집계합니다.
어떤 개선이 "효과 있음"이라고 말하려면 그 차이가 여기서 나오는 변동 폭보다 커야 합니다.

사용: python3 eval-run-variance.py <경로> [<경로> ...]
  경로는 eval-runs-* 디렉터리(run1..runN 포함)이거나, latest-records.json이 있는
  단일 실행 디렉터리(예: target/eval-final)입니다. 같은 코드로 돌린 실행은 빠짐없이
  넘겨야 합니다. 스크립트는 코드가 같은지 확인하지 않습니다.

주의: 실행 수가 적으면 드문 실패(특히 알레르겐 누출)가 0회로 나올 수 있습니다.
0/n은 "없음"이 아니라 "발생률 상한 약 3/n(95%)"입니다. 그래서 n/n이나 0/n에
"안정"이라는 라벨을 붙이지 않고 횟수만 적습니다.
"""
import json, sys, pathlib, collections, statistics, math


def run_dirs(path):
    path = pathlib.Path(path)
    if (path / 'latest-records.json').exists():
        return [path]
    runs = [p for p in path.glob('run*') if p.is_dir()]
    return sorted(runs, key=lambda p: int(''.join(c for c in p.name if c.isdigit()) or 0))


def wilson(k, n, z=1.96):
    if n == 0:
        return 0.0, 1.0
    p = k / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, centre - half), min(1.0, centre + half)


args = sys.argv[1:] or ['.']
runs = [r for a in args for r in run_dirs(a)]
if not runs:
    print(f'{args}에서 실행 디렉터리를 찾지 못했습니다.')
    sys.exit(1)

passes = collections.Counter()
hard_fails = collections.Counter()
seen_runs = collections.Counter()
totals = []

for run in runs:
    records_path = run / 'latest-records.json'
    if not records_path.exists():
        print(f'{run}: 기록 없음, 건너뜀')
        continue
    records = json.loads(records_path.read_text(encoding='utf-8'))
    if isinstance(records, dict):
        records = records.get('records') or records.get('results') or []
    run_pass = 0
    for r in records:
        case = r.get('caseId') or r.get('id') or '?'
        verdict = (r.get('verdict') or '').upper()
        passed = verdict == 'PASS' if verdict else bool(r.get('passed'))
        seen_runs[case] += 1
        if passed:
            passes[case] += 1
            run_pass += 1
        if verdict == 'HARD_FAIL':
            hard_fails[case] += 1
    totals.append((str(run), run_pass, len(records)))

n = len(totals)
if n == 0:
    print('읽을 수 있는 실행이 없습니다.')
    sys.exit(1)

print(f'실행 {n}회 / 케이스 {len(seen_runs)}개')
for label, p, t in totals:
    print(f'  {p:>2d}/{t}  {label}')
print()
print(f'{"케이스":36s} {"통과":>7s} {"HARD_FAIL":>9s}  통과율 95% 구간')
for case in sorted(seen_runs):
    runs_of_case = seen_runs[case]
    p = passes[case]
    lo, hi = wilson(p, runs_of_case)
    flag = '  ★ 결과 갈림' if 0 < p < runs_of_case else ''
    print(f'{case:36s} {p:>3d}/{runs_of_case:<3d} {hard_fails[case]:>5d}/{runs_of_case:<3d}  '
          f'{lo:4.0%}~{hi:4.0%}{flag}')

counts = [p for _, p, _ in totals]
print()
print(f'통과 수: {counts}')
print(f'  최소 {min(counts)}  최대 {max(counts)}  중앙값 {statistics.median(counts):.1f}'
      f'  평균 {statistics.mean(counts):.2f}' + (f'  표준편차 {statistics.stdev(counts):.2f}' if n > 1 else ''))
band = max(counts) - min(counts)
print(f'  변동 폭 {band}개')
print()
hf_total = sum(hard_fails.values())
case_runs = sum(seen_runs.values())
print(f'HARD_FAIL 합계 {hf_total}/{case_runs} (케이스×실행)')
never_hf = [c for c in seen_runs if hard_fails[c] == 0]
if never_hf:
    print(f'  HARD_FAIL 0회인 케이스도 발생률 상한은 약 {3 / n:.0%}입니다(n={n}, 95%).')
print()
print(f'→ 두 설정을 비교할 때 통과 수 차이가 {band}개 이하면 변동 범위 안입니다.')
print(f'→ 효과를 주장하려면 {band + 1}개 이상 차이가 나야 합니다(단일 실행끼리 비교 기준).')
