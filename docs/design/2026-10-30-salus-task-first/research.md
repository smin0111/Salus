# GitHub 디자인 스킬 조사와 적용
2026-10-30

이번 요청은 기존 이미지 초안의 'AI 티'를 줄이는 재설계다. 공개 GitHub 원문을 읽고 설계 원칙을 선별 적용했다. 외부 스킬 설치, 런처·검출기·접근성 검사 실행, 실제 서비스 코드 변경은 하지 않았다.

## 읽은 스킬·가이드

| 자료 | 확인한 원칙 | 이번 적용 |
| --- | --- | --- |
| [Anthropic frontend-design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md) | 제품의 실제 내용에서 시각 방향을 도출. 크림색/세리프, 반복 카드, 불필요한 윗제목·번호·중간점·화살표 같은 관성적 구성을 점검 | 분할 히어로와 3단계 설명 삭제. 화면마다 하나의 실제 작업을 중심으로 구성 |
| [Impeccable distill](https://github.com/pbakaus/impeccable/blob/main/.agents/skills/impeccable/reference/distill.md) | 한 화면의 목표와 주요 행동을 분명히 하고 중복 설명·카드 중첩·복잡한 구조를 줄임 | 랜딩의 여러 진입 버튼을 입력창 하나와 요리 찾기 하나로 통합. 로그인·결제 설명을 짧게 편집 |
| [Impeccable operate](https://github.com/pbakaus/impeccable/blob/main/.agents/skills/impeccable/reference/operate.md) | 작업 화면은 익숙한 조작과 일관성, 한 서체와 좁은 크기 변화, 상태에만 쓰는 강조색 | 흰 작업면, 회색 탐색 영역, 행동/선택에만 녹색. 보조 대화를 좁히고 레시피 본문을 넓힘 |
| [Impeccable craft-floor](https://github.com/pbakaus/impeccable/blob/main/.agents/skills/impeccable/reference/craft-floor.md) | 의미 없는 번호·윗제목·같은 크기 카드·장식 테두리를 거부하고 실제 내용을 렌더해서 검토 | 순서가 있는 조리 단계만 번호를 유지. 별도 독립 비평으로 기존 시안과 새 시안을 실제 시각 검수 |
| [Vercel Web Interface Guidelines](https://github.com/vercel-labs/web-interface-guidelines/blob/main/command.md) | 고정 라벨, 포커스, 체크박스와 라벨의 클릭 영역, 상태/오류/긴 내용 대응 | 입력 라벨과 포커스 의도, 동의 문구와 다음 결제 단계 표시. 실제 코드 준수 여부는 검사하지 않음 |
| [UI/UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/main/.claude/skills/ui-ux-pro-max/SKILL.md) | 모바일 우선순위, 일관된 아이콘·뒤로가기·터치 영역 등 품질 기준 | 모바일을 별도 4상태 보드로 설계하고 레시피를 한 열 문서로 배치. 스크립트/카탈로그 추천 엔진은 실행하지 않음 |
| [taste-skill](https://github.com/senlindesign/taste-skill/blob/main/README.md), [취향 판단 참조](https://github.com/senlindesign/taste-skill/blob/main/references/step3-taste.md) | 색상·숫자 복사에 그치지 않고 실제 선택의 이유와 비용을 설명 | 홍보 면적을 줄이는 대신 첫 사용 행동을 빠르게 제시. 대화 면적을 줄이는 대신 레시피 읽기에 공간 투자 |

원문 참고이며 각 저장소의 제작자가 이 시안을 검증했다는 의미는 아니다. frontend-design/Impeccable의 전체 코드 제작·실행 워크플로를 수행한 것으로 표현하지 않는다.

## 확인한 원문 식별자

- frontend-design SKILL blob: a5333457c414d20d625f307df945842c0952ecc3
- Impeccable SKILL blob: c0dd2530c9968963b1f63bf50b227c4a33996e3b
- operate blob: 524f2c3ae9fa497284e9699f3ca5320cce5d05cf
- distill blob: 2537473ced43e94d5081b61fdced2a4ff602c885
- craft-floor blob: fd7280103f7ef4b009b38ce58f594c44c0f04caa
- Vercel command blob: 3eb3a212b7b89493922ea418489d4c6f67ae8bd5
- taste step3 blob: ced2cb8d0ac225842078ee5df309336513d2fb08

## 독립 비평

별도 에이전트가 이전 최종안 PNG 4장을 실제로 보고 비평했다. 같은 분할 구성 반복, 랜딩의 중복 진입, 추상적인 카피, 대화와 레시피 면적 불균형, 모바일 축소판 문제가 지적됐다.

생성 전 새 계획도 독립 검토했다. 빈 AI 검색창처럼 보이지 않게 실제 2줄 입력 상태를 사용하고, 날짜·끼니를 식사 기록의 실용적 맥락으로 제안했다.

새 데스크톱 3장도 별도 시각 검토했다. 입력 하나/행동 하나, 짧아진 로그인, 넓어진 레시피 본문이 확인됐고 즉시 재생성이 필요한 중대한 레이아웃 결함은 발견되지 않았다. 이는 실제 사용자 테스트나 객관적인 'AI 티 제거' 인증이 아니다.

## 상태와 구현 경계

- 모든 이미지에서 음식 사진·음식 그림을 사용하지 않았다. 데스크톱 4장과 별도 모바일 흐름 1장을 제작했다.
- 랜딩 입력 내용과 날짜, 레시피 내용은 예시다. 모바일의 문자 수 표시는 생성된 예시로 API 제한 확정이 아니다.
- 데스크톱 랜딩은 알레르기 등록 링크만 있어 미입력 상태를 더 명확히 할 필요가 있다. 모바일 시안에는 미입력 문구를 명시했다.
- 출처 없는 레시피는 화면 구성 예시다. 이미지의 저장·기록 버튼이 실제로 저장 가능하거나 검증을 통과했다는 뜻이 아니다. 실제 구현은 기존 서버 검증·저장 가능 상태·알레르기 정책을 유지해야 한다. 새로운 허용/차단 규칙을 정하지 않았다.
- 레시피 조리법·재료량·시간은 검증된 레시피나 의료 조언이 아니다.
- 9,900원은 시안 예시 가격이다. 실제 가격과 판매 가능 상태는 서버 상품 정보와 운영 정책을 따라야 한다.
- 체크된 결제 동의는 선택 후 UI 상태 예시다. 실제 기본 동의 상태나 사용자 동의를 의미하지 않는다.
- 정적 이미지 검수와 PNG 파일 무결성을 확인했다. 실제 폰트·콘트라스트·키보드·스크린리더·포커스·스크롤·동작·성능 테스트는 하지 않았다.
