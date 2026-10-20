# Benchmark tasks (read-only)

`bench.sh run` sends one task block followed by `COMMON`, byte for byte, to every agent and condition.
Edit prompts only between benchmark rounds and record the change in `AGENT_EVALUATION.md` (change log).
Answer keys: `answer-key.md` (never copied into benchmark worktrees).

- **MAIN** (scored, harness effect + practical comparison): A, B, C, D
- **SUPPLEMENTARY** (not part of MAIN totals): S1
- **EFFORT EXPERIMENT** (separate, see `AGENT_EVALUATION.md`): reuses task A with different effort settings; no extra prompt

<!-- prompt:COMMON -->
[벤치마크 공통 제약]
- 읽기 전용 작업이다. 파일을 생성·수정·삭제하지 말고, git 상태를 바꾸는 명령이나 빌드·테스트 실행을 하지 마라.
- 웹 검색을 하지 말고, 현재 저장소 밖의 경로를 탐색하지 마라.
- 최종 답변은 한국어로 작성한다. 결론을 먼저 쓰고, 핵심 주장마다 근거(path:line 또는 path와 클래스/메서드명)를 붙여라.
- 코드에서 확인하지 못한 내용은 추측하지 말고 "미확인"으로 표시하라.
<!-- /prompt:COMMON -->

## MAIN

### A. Architecture navigation
<!-- prompt:A -->
[과제 A] Salus에서 사용자의 채팅 HTTP 요청이 LLM 기반 구조화 레시피 생성까지 도달하는 흐름을 추적하라.
진입 API부터 LLM 호출, 결과 검증, 응답 반환까지 핵심 클래스와 메서드를 호출 순서대로 제시하라.
전체 저장소를 읽지 말고 필요한 코드만 조사하라.
<!-- /prompt:A -->

### B. Allergen trace
<!-- prompt:B -->
[과제 B] Salus에서 사용자의 알레르기 정보와 레시피 재료 텍스트가 "알레르기 충돌" 판정(차단 또는 경고)으로 바뀌는 경로를 추적하라.
알레르기 정보의 출처, 알레르겐 사전 데이터, 매칭 규칙, 판정 결과가 사용자 응답에 반영되는 지점, 이를 검증하는 테스트를 찾아 각각의 역할을 설명하라.
<!-- /prompt:B -->

### C. Database trace
<!-- prompt:C -->
[과제 C] Salus의 식단 기록(MealLog) 도메인에 대해 DB 테이블 정의(Flyway) → JPA Entity → Repository → Service → REST API 흐름을 추적하라.
식단 저장 요청 한 건이 어떤 사용자 식별, 검증, 트랜잭션을 거쳐 저장되는지 설명하고 관련 테스트 위치도 제시하라.
<!-- /prompt:C -->

### D. Test navigation
<!-- prompt:D -->
[과제 D] Salus에서 "한 글자 알레르겐(예: 밀)이 다른 단어(예: 밀크티)에 부분 문자열로 오탐되지 않는다"는 동작을 검증하는 테스트를 찾아라.
테스트 메서드, 검증 대상 프로덕션 코드 위치, 그 테스트 하나만 실행하는 가장 좁은 명령과 실행 전제 조건을 제시하라.
테스트는 실행하지 말고 명령만 제시하라.
<!-- /prompt:D -->

## SUPPLEMENTARY

### S1. Change impact analysis (application LLM thinking setting; not about agent effort)
<!-- prompt:S1 -->
[과제 S1] Salus에서 Ollama 모델의 thinking 모드 사용 여부를 결정하는 로직을 찾아라.
그 로직을 변경하면 영향을 받는 호출 경로(채팅 응답, 레시피 생성 등)와 이를 검증하는 테스트를 나열하고, 변경 시 지켜야 할 제약을 코드 근거와 함께 설명하라.
<!-- /prompt:S1 -->
