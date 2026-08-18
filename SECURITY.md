# 보안 정책

Salus는 건강정보, 알레르기, 복용약, 식단 기록과 결제 식별자처럼 민감할 수 있는 정보를 다룹니다. 보안 문제는 공개 이슈보다 비공개 신고를 우선합니다.

## 취약점 신고

비밀키, 토큰, 개인정보, 실제 사용자 데이터가 포함된 내용을 공개 GitHub Issue나 PR에 올리지 마세요. 저장소 소유자의 GitHub 프로필에 표시된 비공개 연락 수단으로 재현 조건, 영향 범위, 필요한 최소 로그만 전달해 주세요. 별도 보안 이메일은 아직 운영하지 않습니다.

## 비밀정보 관리

- 실제 `.env`, `backend/src/main/resources/application-secret.properties`, `frontend/src/secrets.js`는 커밋하지 않습니다.
- `.env.example`, `application-secret.example.properties`, `secrets.example.js`에는 동작하지 않는 예시값만 둡니다.
- JWT secret, OAuth client secret, PortOne·Gemini·Tavily·MFDS·YouTube API 키, DB 접속정보는 환경변수나 배포 플랫폼의 secret 저장소로 주입합니다.
- 비밀값이 커밋되었다면 파일만 지우지 말고 즉시 폐기·재발급한 뒤 Git 이력 노출 범위를 점검합니다.

## JWT와 관리자 접근

현재 관리자 콘솔은 `ADMIN` 권한 사용자의 JWT를 사용합니다. 관리자 JWT를 메신저, 스크린샷, Issue, PR 설명에 남기지 말고 권한 계정을 최소화하세요. 운영에서는 짧은 만료 시간, CORS 제한, 배포 네트워크를 확인한 IP allowlist를 함께 검토해야 합니다. 권한 변경 전에는 대상 사용자를 고유 식별하고, 변경 후 `/api/admin/dashboard/auth-check`로 확인합니다.

## 민감 데이터와 결제정보

- 건강정보·알레르기·복용약은 필요한 범위에서만 조회하고 응답 생성의 안전 조건으로 취급합니다.
- 실제 개인정보나 운영 데이터를 fixture, SQL dump, 테스트 리소스에 복사하지 않습니다.
- 결제 검증은 클라이언트 값을 신뢰하지 않고 결제사의 상태·주문번호·금액을 서버에서 대조합니다.
- 결제사 응답 본문, 결제 식별자, 건강정보, 사용자 원문은 애플리케이션 로그에 직접 남기지 않습니다. 장애 로그에는 요청 추적값, 범주, 길이, 해시, 예외 타입 등 최소 메타데이터만 남깁니다.

## 배포 전 확인

- `mvn clean test`, 프론트/어드민 빌드, `npm audit`, Docker Compose config와 이미지 빌드를 확인합니다.
- Flyway migration을 스키마의 기준으로 사용하며 운영 DB dump나 임시 SQL을 저장소에 추가하지 않습니다.
- Recipe Agent 외부 소스와 live medication 평가는 필요한 키와 운영 검토가 끝난 환경에서만 명시적으로 활성화합니다.

지원 범위는 `main`과 실제 배포 준비 중인 브랜치입니다. 오래된 실험 브랜치는 보안 수정 대상에서 제외될 수 있습니다.
