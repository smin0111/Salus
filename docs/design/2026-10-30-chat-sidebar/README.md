# 채팅 기록 사이드바

Codex처럼 대화 목록을 보면서 채팅할 수 있도록 변경했습니다.

- 768px 이상: 왼쪽 260px 사이드바 기본 표시. 접기·펼치기 가능.
- 모바일: 같은 목록을 접이식 패널로 표시. 대화를 선택하면 패널을 닫습니다.
- 새 채팅 / 최근 대화 / 선택한 대화 표시. 각 행의 `…` 메뉴에서 이름 변경·삭제로 이동합니다.
- 상단 다섯 메뉴와 기존 채팅·레시피·서버 요청 로직은 유지합니다.
- 데스크톱 접힘 상태와 모바일 열림 상태를 분리합니다. 채팅 입력 영역의 key를 고정해 패널 전환 중 초안을 유지합니다.

변경 파일은 `ChatHistorySidebar.js`, `AppShell.js`, `ChatScreen.js`, `AppNavigator.js` 네 개입니다. 랜딩 파일은 수정하지 않았습니다.

## 검증

- 웹 export 통과: `EXPO_PUBLIC_API_BASE_URL=/api npx --no-install expo export --platform web --output-dir /tmp/salus-ui-final-export`.
- Babel parser 4개 파일 및 `git diff --check` 통과.
- localhost 합성 데이터로 855×755, 1280×800, 375×812 브라우저 확인.
- 기본 목록 표시, 접기·펼치기와 입력 초안 유지, 대화 선택 후 데스크톱 목록 유지, 모바일 패널 닫힘, 옵션 메뉴와 이름 변경 모달 진입·취소 확인.
- 삭제 확인창 열림 확인. 브라우저 자동 조작이 네이티브 확인창에서 정지하여 사용자가 취소했습니다. 실제 삭제 요청은 실행하지 않았습니다.
- 모바일 문서 가로 폭 375px로 넘침 없음. 임시 화면 크기를 해제하고 원래 미리보기를 새 빌드로 갱신했습니다.
- 독립 읽기 전용 리뷰 `salus-chat-sidebar-1030`: 첫 완료 리뷰 pass, findings 0건.
- 실제 API 및 iOS·Android 네이티브 실행은 검증하지 않았습니다. 백엔드 변경과 백엔드 테스트 실행은 없습니다.

![데스크톱](/Users/iseungmin/Downloads/project/Salus/docs/design/2026-10-30-chat-sidebar/desktop.jpg)

![모바일](/Users/iseungmin/Downloads/project/Salus/docs/design/2026-10-30-chat-sidebar/mobile.jpg)
