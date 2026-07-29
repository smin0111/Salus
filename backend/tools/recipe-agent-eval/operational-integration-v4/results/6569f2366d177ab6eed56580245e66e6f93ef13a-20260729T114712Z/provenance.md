# Recipe Agent operational evaluation v4 provenance

- source SHA: `6569f2366d177ab6eed56580245e66e6f93ef13a`
- source branch: `feature-레시피에이전트-0729`
- clean 확인: `git status --porcelain=v1 -uall` 출력 0개, staged 0개, `git diff --check` 성공
- Runner 출처: 원본 working-tree v3 Runner를 외부 prep 디렉터리에 복사하고 clean HEAD wiring 및 v4 coverage metric만 보강
- fixture 출처: 원본 70-case fixture의 외부 복사본; `multi-med-food-condition-and-api-failed`를 동일 ID의 multi-turn case로 강화
- 기존 v3 결과 재사용: 하지 않음. 현재 source SHA에서 70 case를 새로 실행
- 외부 API: 비활성화. Runner의 fixture `ExchangeFunction`, fake adapter 및 저장된 비민감 fixture만 사용
- production source: `35`개 파일, aggregate `5cc72768bf5d2511b5b35e13ea7274f9af63cdf94833edccf76987e8cb8d33c3`
- hash 방식: 각 파일 원문 bytes의 SHA-256, manifest 행은 상대경로 오름차순
- latency 한계: fixture-backed in-process operational evaluation latency이며 실제 외부 서비스 또는 사용자 end-to-end 응답시간으로 해석할 수 없음
