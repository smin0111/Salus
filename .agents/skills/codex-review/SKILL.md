---
name: codex-review
description: Run Salus implementation review through the local read-only Codex CLI and evaluate the findings in the current Claude conversation. Use after implementation and verification, before completion or a commit request; also supports /codex-review. Do not use for read-only questions or recursively from a Codex review.
---

# Claude 구현 → Codex 검토 → Claude 검증

명령·브리프·종료 코드는 `docs/ai/CODE_REVIEW_LOOP.md`를 읽습니다.

- 현재 Claude 대화에서 진행합니다. 별도 Claude 프로세스나 다른 채팅을 시작하지 않습니다.
- 이번 작업의 변경 파일(새 파일·삭제 파일 포함)만 `--paths`에 명시합니다. 전체 작업 트리를 자동으로 넣지 않습니다. 필요한 의존 코드는 `--include`로 추가합니다.
- `.codex-review/`의 브리프에 목표, 합의, 실제 검증 결과, 미확인 사항과 승인 범위를 씁니다. 별도 Codex 세션은 현재 대화를 자동으로 보지 못합니다. 자격 증명·개인정보·운영 로그·환경 파일은 넣지 않습니다.
- 작업 내내 동일한 `--job`으로 `scripts/review/codex_review.py`를 실행합니다. 명시적 스냅숏을 읽기 전용으로 검토하며, 승인 우회나 쓰기 가능한 검토자로 바꾸지 않습니다.
- 지적은 코드로 검증해 수정하거나 `--reply`에 반박 근거를 씁니다. 필요한 검증 후 브리프를 갱신하고 같은 job으로 재검토합니다. stale 결과는 승인 근거로 사용하지 않습니다.
- 완료된 검토는 최대 3회입니다. 한도·연결·서버 오류와 시간 초과는 완료 회차를 소모하지 않습니다. 처음 3회 연속 실행 실패 뒤에는 1시간 후 한 번 재시도하고, 계속 실패하면 매번 1시간 뒤에 재시도합니다. 모델은 job 시작 시 고정하며, API 키·다른 모델·추가 결제로 전환하지 않습니다.
- 종료 코드 5는 로컬 백그라운드 재시도 대기입니다. 중복 작업을 만들지 않습니다. `--status`로 상태를 보고 `--cancel`로 중단할 수 있습니다. 입력이 바뀌면 자동 중단합니다. 중지·재부팅 후에는 `--job <ID> --resume`으로 저장된 입력을 복원합니다. 입력이 달라졌으면 stale로 멈추므로 새 브리프를 확인합니다. Claude 데스크톱 자체의 한도 해제 후 대화 자동 재개는 보장하지 않습니다.
- 인증·설정·출력 형식 오류, 미해결 설계 의견 차이, 부족한 증거·권한, 완료 리뷰 3회 한도는 사용자에게 보고합니다. 상태 삭제나 새 job으로 한도를 우회하지 않습니다.
- pass는 해당 범위의 검토 결과입니다. 커밋·푸시·PR·병합·배포·Funnel 변경·기능 활성화 권한은 사용자 지시와 AGENTS.md를 따릅니다.
- 결과와 미확인 사항은 한국어로 보고하고, 이메일이나 알림 자동화는 만들지 않습니다.

- "자동화프로젝트 시작해"는 마지막 승인 범위의 개발·검증을 재개하는 지시입니다. 로컬 인계 기록과 job 상태를 확인해 이어가며, 커밋·푸시·PR 권한으로 해석하지 않습니다. "멈춰"를 받으면 예약된 재시도도 취소하고 인계 기록을 남깁니다. 완료 보고 뒤 Git 작업을 매번 권유하지 않습니다.
