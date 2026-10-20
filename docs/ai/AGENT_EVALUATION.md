# Agent 평가 프로토콜: Claude Code vs Codex, 최적화 전/후

목표는 "최소 토큰"이 아니라 **같거나 더 좋은 품질을 더 적은 context/token으로** 얻는지 확인하는 것이다.
이 문서는 사람용 절차서다. 에이전트가 일반 작업 중에 읽을 필요는 없다.

## 1. 구성 파일
| 파일 | 역할 |
|---|---|
| `docs/ai/bench/tasks.md` | 모든 조건에 글자 그대로 보내는 프롬프트: MAIN(A~D), SUPPLEMENTARY(S1), 공통 제약 |
| `docs/ai/bench/answer-key.md` | 채점 기준(필수 사실, 근거 위치, 오답 트랩). 벤치마크 worktree에 복사하지 않음 |
| `docs/ai/bench/bench.sh` | worktree 준비(`prepare`), 점검(`check`), 실행 계획 출력(`dry-run`), 실행(`run`), 목록(`list`) |
| `docs/ai/bench/pilot.sh` | 로컬 검증 + A/D 파일럿: `preflight`(모델 호출 없음), `run`(로딩 확인 + 파일럿 + 지표 + WIP 확인) |
| `docs/ai/bench/metrics.py` | 로그 → 지표(`collect`), 원본 로그 필드 확인(`fields`), 채점표와 합쳐 요약(`summarize`) |
| `docs/ai/bench/scores-template.csv` | 수기 채점표 양식 |
| `docs/ai/bench/variants/CLAUDE.before.md` | 최적화 전 CLAUDE.md 원문(2026-08-21 작성본) |

## 2. 비교 조건
| 조건 | 에이전트 | worktree에 들어가는 지침 |
|---|---|---|
| `claude-base` (선택) | Claude Code | 없음 |
| `claude-before` | Claude Code | `CLAUDE.md` = 최적화 전 원문, skills 없음 |
| `claude-after` | Claude Code | `CLAUDE.md`(`@AGENTS.md` import) + `AGENTS.md` + `docs/ai/{PROJECT_CONTEXT,DEVELOPMENT_RULES,SAFETY_RULES}.md` + `.claude/skills/*` |
| `codex-base` | Codex | 없음 |
| `codex-optimized` | Codex | `AGENTS.md` + 같은 `docs/ai` 3개 + `.agents/skills/*` |

`CLAUDE.local.md`(개인 설정)는 어떤 조건에도 넣지 않는다. 현재 작업 트리의 `CLAUDE.md`/`AGENTS.md`는 건드리지 않고 worktree 안에서만 조건을 만든다.

## 3. 분석은 두 가지로 분리한다
| 분석 | 비교 | 바뀌는 것 | 허용되는 결론 |
|---|---|---|---|
| **A. Harness Effect** | `claude-before` vs `claude-after`, `codex-base` vs `codex-optimized` | 같은 에이전트·같은 모델 설정에서 지침만 | "이 에이전트에서 하네스 적용 후 지표가 이렇게 달라졌다" |
| **B. Practical Agent Comparison** | `claude-after` vs `codex-optimized` | 에이전트와 모델 전체 | "같은 지식 기반을 줬을 때 실제 사용 특성이 이렇게 다르다"(서술만) |

해석 규칙:
- B에서 "하네스 때문에 Claude/Codex가 더 좋다" 같은 인과 결론을 내리지 않는다. 모델·도구·토큰 정의가 모두 다르다.
- A는 에이전트 안에서만 비교한다. Claude의 개선 폭과 Codex의 개선 폭을 직접 비교하지 않는다(토큰 정의가 다르다).
- 한 번에 한 가지만 바꾼다. 하네스 비교 중에는 모델·effort를 고정하고, effort 실험 중에는 하네스를 고정한다.
- 조건×과제당 3회 미만이면 요약은 서술용이다. "N% 절감", "더 정확하다" 같은 표현을 쓰지 않는다.
- `metrics.py summarize`는 MAIN 과제만 집계하고 A/B 표를 따로 출력한다.

## 4. 공정성 규칙
- **같은 커밋, 깨끗한 트리**: 기본 `7aaeefc`에서 `git worktree add --detach`로 만든 worktree(브랜치 생성 없음). 현재 작업 트리의 WIP는 계속 바뀌므로 쓰지 않는다.
- **같은 프롬프트**: 과제 블록 + `COMMON`을 그대로 전송(`prompt.txt`로 보관). 조건별 힌트 금지.
- **새 세션**: run마다 새 비대화형 세션(`claude -p`, `codex exec`). Claude는 worktree 간 공유되는 auto memory를 끈다(`CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`).
- **같은 권한**: 읽기 전용. Claude는 `--permission-mode dontAsk` + 읽기 도구·읽기 명령만 허용, 편집·웹 도구 차단. Codex는 `--sandbox read-only`. 설치 버전의 help에서 플래그를 먼저 확인한다(`pilot.sh preflight`).
- **같은 제한**: 과제당 `BENCH_TIMEOUT_SECONDS`(기본 900초, macOS는 perl alarm 사용), 웹 금지, 저장소 밖 접근 금지.
- **환경 기록**: 전역 지침/스킬 존재 여부, CLI 버전, 추가 인자를 `meta.json`에 자동 기록한다. 전역 MCP·플러그인은 가능하면 끈 상태로 실행하고 `notes`에 적는다.
- **반복과 순서**: 본 실행은 조건×과제당 최소 3회, run마다 조건 순서를 바꾼다(ABBA). 파일럿은 1회.
- **오염 방지**: 답안 키는 worktree에 없다. 저장소 밖을 읽으면 `outside_repo_reads`>0 → 자동 위반.

## 5. 과제
**MAIN** (모두 읽기 전용, 기준 커밋 `7aaeefc`)

| ID | 측정하는 능력 | 기준 도구 호출 수 |
|---|---|---|
| A | 아키텍처 탐색: 채팅 요청 → LLM 구조화 레시피 생성 → 검증 → 응답 | 30 |
| B | 안전 경로 추적: 알레르기 출처 → 사전 → 매칭 규칙 → 차단 지점 → 테스트 | 25 |
| C | DB 추적: Flyway → Entity → Repository → Service → API (MealLog) | 20 |
| D | 테스트 탐색: 특정 동작의 테스트와 가장 좁은 실행 명령 (실행 금지) | 12 |

**SUPPLEMENTARY** (MAIN 합계에서 제외): S1 애플리케이션의 Ollama thinking 설정 변경 영향 분석(기준 15). 에이전트 effort 실험이 아니다.

**EFFORT EXPERIMENT** (하네스 비교와 분리)
| ID | 조건 | 바꾸는 것 | 실행 예 |
|---|---|---|---|
| E1 | `claude-after`, 과제 A, 수준별 3회 | Claude effort만 | `BENCH_LABEL=effort-low BENCH_CLAUDE_ARGS="<설치 버전의 effort 플래그> low" bash docs/ai/bench/bench.sh run claude-after A 1` |
| E2 | `codex-optimized`, 과제 A, 수준별 3회 | Codex reasoning effort만 | `BENCH_LABEL=effort-low BENCH_CODEX_ARGS="-c model_reasoning_effort=low" bash docs/ai/bench/bench.sh run codex-optimized A 1` |

설정 이름은 `pilot.sh preflight`가 저장한 help 출력으로 확인한다. 설치 버전에 해당 설정이 없으면 실행하지 않고 설계로만 둔다. 모델은 고정한다.

기준 도구 호출 수는 초기값이다. 첫 본 라운드의 중앙값으로 재보정하고 11장에 기록한다.

## 6. 지표와 수집 방법
로그가 제공하지 않는 값은 `N/A`로 남기고 추정하지 않는다. 필드 위치는 실제 로그로 확인한다: `python3 docs/ai/bench/metrics.py fields <results-dir>`.

| 지표 | Claude Code (`stream-json`) | Codex (`exec --json`) |
|---|---|---|
| input tokens | `result.usage.input_tokens` (캐시 제외분) | `turn.completed.usage.input_tokens` 합 (캐시 포함) |
| cache read / write | `cache_read_input_tokens` / `cache_creation_input_tokens` | `cached_input_tokens` / `cache_write_input_tokens` |
| 비교용 총 입력 | input + cache read + cache write | input_tokens (cached > input이면 `CHECK`) |
| output tokens | `output_tokens` (thinking 포함, 분리 불가) | `output_tokens` |
| reasoning tokens | N/A | `reasoning_output_tokens` (output에 더하지 않음) |
| cost | `total_cost_usd` (클라이언트 추정치) | N/A |
| 시간 | `duration_ms` + bench.sh wall clock | wall clock |
| tool calls | `tool_use` 블록 수(중복 id 제거, subagent 분리 집계) | `item.completed` 중 명령/도구 항목 수 |
| files inspected, full/targeted/repeated reads | `Read`(offset/limit 유무) + Bash 읽기 명령 해석 | 셸 명령 해석(`cat`/`nl` 전체, `sed -n`/`head`/`tail`/파이프 제한은 부분) |
| read output chars | 읽기 호출의 `tool_result` 글자 수 합 | 읽기 명령의 `aggregated_output` 글자 수 합 |
| commands run | Bash 명령 분류(search/listing/git/build/write) | 동일 |
| 위반 | worktree 변경, 저장소 밖 읽기, 쓰기/빌드/git 변경/웹 호출 | 동일 |
| correctness, evidence, hallucination | 수기 채점(7장) | 동일 |
| unnecessary exploration | `large_full_reads` + `out_of_scope_reads` + `excluded_path_reads` | 동일 |

ccusage(`npx ccusage@latest claude session`, `npx ccusage@latest codex session`)는 교차 확인용이다. 벤치마크의 1차 근거는 run별 원본 로그다.

## 7. 품질 채점 (100점)
| 항목 | 배점 | 판정 기준 |
|---|---|---|
| Correctness | 30 | `30 × (충족한 필수 사실 / 필수 사실 수)` − 사실 오류 1건당 5 − 안전 치명 오류 1건당 10, 하한 0. 필수 사실·오류 목록은 answer-key 기준, 목록 밖 오류는 코드로 확인한 경우만 인정 |
| Evidence / traceability | 20 | 근거 부착률(필수 사실 주장 중 path:line 또는 path+메서드가 붙은 비율) × 12 + 무작위 근거 3개를 기준 커밋에서 확인해 맞는 개수 × 2(파일·클래스·메서드가 맞으면 인정, 줄 번호는 보조) + 불확실성 표시 2 |
| Task completion | 20 | answer-key "완료 체크"를 모두 충족하면 20, 누락 1개당 −5. 제약 위반·최종 답변 없음·timeout이면 0 |
| Context efficiency | 15 | 자동: 300줄 초과 파일 전체 읽기 0회 5 / 1~2회 3 / 3회+ 0; 반복 읽기 비율 ≤0.10 5 / ≤0.25 3 / 초과 0; 범위 밖 읽기 ≤2 5 / ≤5 3 / 초과 0 |
| Tool efficiency | 10 | 자동: 도구 호출 ≤ 기준 10 / ≤1.5배 6 / ≤2배 3 / 초과 0; 같은 도구+같은 입력 반복 2회 이상이면 −2 |
| Clarity | 5 | 결론 먼저 1 + 순서·구조를 목록/표로 제시 2 + 원본 출력 덤프나 과제와 무관한 장문 없음 2 |

- **성공 run** = 총점 ≥ 70, Correctness ≥ 21, 제약 위반 없음.
- 채점자는 조건 이름을 가린 `final.md`로 먼저 Correctness/Evidence/Completion/Clarity를 매긴다(블라인드 채점).

## 8. 실행 절차
### 8.1 파일럿 (A, D × 4조건 × 1회)
```bash
cd ~/Downloads/project/Salus
bash docs/ai/bench/pilot.sh preflight   # 모델 호출 없음. 결과: ../Salus-harness-pilot/preflight.log
# preflight.log에서 CLI 플래그, 스킬 동기화, dry-run 명령을 확인한 뒤
bash docs/ai/bench/pilot.sh run         # 로딩 확인 2회 + 파일럿 최대 8회 + 지표 + WIP 확인
```
파일럿 통과 조건. 하나라도 실패하면 본 실행으로 넘어가지 않는다.
- 두 CLI 모두 비대화형 명령이 성공하고 `result`/`turn.completed` 이벤트가 있다.
- 조건별로 의도한 지침 파일만 worktree에 있고(`bench.sh check`), 스킬이 발견된다.
- `events.jsonl`, `meta.json`, `final.md`가 모두 생기고 `metrics.py collect`/`fields`가 오류 없이 끝난다.
- 토큰 필드가 실제 로그 필드에서 읽힌다(값이 없으면 N/A).
- 답안 키 A/D의 필수 사실이 기준 커밋과 맞는다.
- `wip-manifest-run-start.txt`와 `wip-manifest-run-end.txt`가 같고, 결과가 저장소 밖(`../Salus-harness-pilot`)에만 쌓인다.

### 8.2 본 실행 (파일럿 통과 후)
```bash
for run in 1 2 3; do
  if [ $((run % 2)) -eq 1 ]; then order="claude-before claude-after codex-base codex-optimized"
  else order="codex-optimized codex-base claude-after claude-before"; fi
  for task in A B C D; do for v in $order; do bash docs/ai/bench/bench.sh run "$v" "$task" "$run"; done; done
done
python3 docs/ai/bench/metrics.py collect docs/ai/bench/results -o docs/ai/bench/results/metrics.csv
cp docs/ai/bench/scores-template.csv docs/ai/bench/results/scores.csv   # 블라인드 채점 입력
python3 docs/ai/bench/metrics.py summarize docs/ai/bench/results/metrics.csv docs/ai/bench/results/scores.csv -o docs/ai/bench/results/summary.md
```
worktree 정리는 결과 보존을 확인한 뒤 직접 판단한다(`git worktree list`, `git worktree remove <path>`; 복사된 지침 파일 때문에 거부되면 `--force` 여부를 직접 결정).

## 9. 결과 저장 형식
```text
<BENCH_RESULTS_DIR>/<YYYYMMDD>/<condition>[+<label>]/<task>-r<n>/
  prompt.txt  events.jsonl  final.md  stderr.log  meta.json  status-before.txt  status-after.txt
metrics.csv   run별 자동 지표      scores.csv   수기 채점      summary.md   분석 A/B 표
```
기본 `BENCH_RESULTS_DIR`은 `docs/ai/bench/results`(gitignore), 파일럿은 `../Salus-harness-pilot/results`.

## 10. 한계와 알려진 비대칭
- 표본이 작고 모델 출력은 비결정적이다. 조건 간 차이가 run 간 편차보다 작으면 결론을 내리지 않는다.
- 두 에이전트의 토큰 정의가 다르다(6장). 절대값보다 같은 에이전트의 전/후 비교가 더 신뢰할 만하다.
- 답안 키는 `7aaeefc` 기준이다. WIP(알레르기 표시 파서, 승인 카탈로그, eval harness 등)가 커밋되면 기준 커밋과 답안 키를 함께 갱신한다.
- `claude-before` 원문과 `docs/ai` 문서 모두 `7aaeefc`에 없는 WIP 내용을 일부 언급한다. 에이전트가 이를 코드로 확인하지 않고 "존재한다"고 쓰면 사실 오류로 채점한다.
- 구독형 사용은 비용이 N/A다. 비용 비교는 Claude의 추정치에만 해당한다.

## 11. 변경 이력
| 날짜 | 변경 |
|---|---|
| 2026-09-17 | 초판: 과제 A~E, 기준 커밋 `7aaeefc`, 채점표 v1, 도구 호출 기준 초기값 |
| 2026-09-17 | MAIN을 A~D로 한정, 기존 E를 S1(보조)로 이동, EFFORT 실험(E1/E2) 분리, 분석 A(Harness Effect)/B(Practical Comparison) 분리, `pilot.sh`·`dry-run`·`fields`·`BENCH_RESULTS_DIR`·`BENCH_LABEL`·read output chars 추가 |
