#!/usr/bin/env python3
"""재료 필드 순서 5가지를 1회씩 돌린다(각 약 30분). 사용: python3 scripts/eval-order-experiment.py
 순서 고정 후 결과가 결정적이므로 n=1로 충분하다.
원본 RecipePromptFactory는 백업해 두고 끝나면(실패해도) 반드시 복원한다."""
import pathlib, shutil, subprocess, sys, json, glob, os

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / 'backend/src/main/java/com/salus/healthytable/service/RecipePromptFactory.java'
SCRATCH = REPO / 'backend/target/eval-order-experiment'
SCRATCH.mkdir(parents=True, exist_ok=True)
BACKUP = SCRATCH / 'RecipePromptFactory.java.orig'

LINES = {
    'name': '                "name", Map.of("type", "string", "maxLength", 40),\n',
    'amount': '                "amount", Map.of("type", List.of("number", "null")),\n',
    'unit': '                "unit", Map.of("type", "string", "enum", ALLOWED_UNITS),\n',
}
ORIGINAL_BLOCK = LINES['name'] + LINES['amount'] + LINES['unit']
ORDERS = [
    ('name', 'unit', 'amount'),
    ('unit', 'name', 'amount'),
    ('unit', 'amount', 'name'),
    ('amount', 'name', 'unit'),
    ('amount', 'unit', 'name'),
]

original = SRC.read_text(encoding='utf-8')
assert original.count(ORIGINAL_BLOCK) == 1, 'ingredient 순서 블록을 찾지 못함'
shutil.copyfile(SRC, BACKUP)
summary = {}
try:
    for order in ORDERS:
        label = '/'.join(order)
        SRC.write_text(original.replace(ORIGINAL_BLOCK, ''.join(LINES[k] for k in order)), encoding='utf-8')
        before = set(glob.glob(str(REPO / 'backend/target/eval-runs-*')))
        print(f'=== {label}', flush=True)
        subprocess.run([str(REPO / 'scripts/eval-repeat-runs.sh'), '1', 'qwen3:8b', 'gemma3:4b'],
                       cwd=REPO, check=False)
        new = sorted(set(glob.glob(str(REPO / 'backend/target/eval-runs-*'))) - before)
        summary[label] = new[-1] if new else None
        print(f'--- {label} -> {summary[label]}', flush=True)
finally:
    SRC.write_text(original, encoding='utf-8')
    print('원본 RecipePromptFactory 복원 완료', flush=True)

(SCRATCH / 'order_experiment_dirs.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print(json.dumps(summary, ensure_ascii=False, indent=2))
