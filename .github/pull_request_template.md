## 요약

-

## 왜 필요한가요?

-

## 변경 사항

-

## 사용자 영향

- [ ] 사용자가 보는 화면 또는 문구가 바뀝니다
- [ ] API 요청/응답 형식이 바뀝니다
- [ ] 데이터베이스 스키마 또는 저장 데이터가 바뀝니다
- [ ] 운영 설정 또는 배포 방식이 바뀝니다
- [ ] 내부 구조만 바뀌며 사용자 영향은 없습니다

## 검증 방법

실행한 항목만 체크합니다.

- [ ] `mvn test` in `backend`
- [ ] `npm run build` in `admin`
- [ ] `npx expo export --platform web --output-dir dist` in `frontend`
- [ ] `npm audit --audit-level=moderate` in `admin`
- [ ] `npm audit --audit-level=high` in `frontend`
- [ ] 수동 확인

## 위험 영역

- [ ] 인증 또는 권한
- [ ] 결제
- [ ] 개인정보
- [ ] 건강정보, 알레르기, 식단 안전
- [ ] 데이터베이스 마이그레이션
- [ ] 외부 API 또는 AI 응답
- [ ] Docker 또는 배포 설정
- [ ] UI만 변경

## 배포 전 확인

- [ ] 실제 비밀키, `.env`, `application-secret.properties`를 커밋하지 않았습니다
- [ ] 새 환경변수는 `.env.example` 또는 예시 설정에 문서화했습니다

## 리뷰어에게 남길 말

-
