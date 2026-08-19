# Salus

Salus는 냉장고 재료, 식단 기록, 건강 프로필과 알레르기 정보를 한 흐름에서 관리하고, 근거를 확인한 레시피를 제공하려는 개인 식생활 관리 서비스입니다. 핵심 문제는 “무엇을 먹을지”와 “내 조건에서 먹어도 되는지”가 서로 다른 앱과 기억에 흩어지는 점입니다.

> 이 저장소는 포트폴리오 및 개발 검증 단계입니다. 의료 진단·처방을 제공하지 않으며, 생성 결과는 전문 의료인의 판단을 대체하지 않습니다.

## 구현 범위

현재 코드와 자동화 테스트로 확인하는 범위:

- JWT 인증, 소셜 로그인 연결부, 사용자·건강 프로필·건강검진 관리
- 냉장고 재료, 식단 기록, 커뮤니티, 결제 검증, 관리자 API/웹
- 채팅 세션과 작업 세션, 내부 DB/공식·웹 근거 검색, 구조화 레시피 생성·검증·감사 기록
- 알레르기 충돌 차단, 재료 제외·대체, 상세 설명, 식단 캘린더 저장 후속 요청
- Flyway 기반 스키마 변경, Redis 작업 세션, CI·Dependabot, 컨테이너 빌드

검증 중이거나 기본 비활성화된 범위:

- `Recipe Agent` 초기 라우팅과 외부 source discovery
- YouTube transcript, MFDS 의약품, RxNorm, openFDA를 이용한 live medication evidence
- 실제 운영 트래픽에서의 임상적 안전성, 외부 API 가용성·정확성, 모바일 스토어 배포

## 구조

```text
Salus/
├── backend/                 # Spring Boot API, 도메인, JPA Repository, Flyway
├── frontend/                # Expo/React Native 사용자 앱
├── admin/                   # Vite/React 관리자 웹
├── .github/                 # CI, Dependabot, Issue/PR 템플릿
├── docker-compose.yml       # 로컬 Redis
└── docker-compose.prod.yml  # MySQL, Redis, backend, admin
```

## 실제 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 17, Spring Boot 3.2.1, Spring MVC, WebFlux `WebClient`, Spring Security, JWT |
| Data | Spring Data JPA, Hibernate, MySQL 8.0, Flyway, Redis `StringRedisTemplate` |
| AI/Search | Ollama, 선택적 Gemini·Tavily, DuckDuckGo HTML search, 선택적 MFDS/YouTube/RxNorm/openFDA adapter |
| User app | Expo 54, React Native 0.81, React 19 |
| Admin | React 19, Vite 6, React Router 7 |
| Quality/Operations | JUnit 5, Mockito, Maven, npm, GitHub Actions, Dependabot, Docker Compose |

Salus의 영속성 계층은 **Spring Data JPA · Hibernate**입니다. MyBatis mapper, mapper XML, MyBatis dependency는 사용하지 않습니다. Maven 좌표와 Java package는 `com.salus`를 사용합니다.

## 사전 조건

- Java 17+
- Maven 3.9+
- Node.js 20 / npm
- Docker Desktop(Compose v2 포함)
- 로컬 LLM을 사용할 경우 [Ollama](https://ollama.com/)와 `qwen3:8b` 모델

## 로컬 실행

### 1. 예시 환경변수와 인프라

```bash
cp .env.example .env
```

`.env`의 DB 비밀번호, JWT secret, CORS origin, PortOne 예시값을 로컬 값으로 교체하세요. `.env`는 Git에서 제외되며 커밋하면 안 됩니다.

MySQL과 Redis만 먼저 실행할 수 있습니다.

```bash
docker compose --env-file .env -f docker-compose.prod.yml up -d mysql redis
```

Redis만 필요하면 더 작은 개발 구성을 사용합니다.

```bash
docker compose up -d redis
```

### 2. Ollama

```bash
ollama pull qwen3:8b
ollama serve
```

호스트에서 백엔드를 실행할 때 기본 주소는 `http://localhost:11434/api/chat`입니다. Docker 백엔드에서 호스트 Ollama를 사용할 때는 `.env.example`처럼 `host.docker.internal`을 사용합니다.

### 3. Backend

Compose용 `.env`를 현재 셸에도 불러온 뒤 실행하면 MySQL 계정과 JWT 설정이 일치합니다.

```bash
set -a
source .env
set +a
cd backend
mvn spring-boot:run
```

또는 `backend/src/main/resources/application-secret.example.properties`를 `application-secret.properties`로 복사해 로컬 비밀값을 넣을 수 있습니다. 실제 파일은 커밋하지 않습니다. API 기본 주소는 `http://localhost:8080`, health endpoint는 `/actuator/health`입니다.

스키마의 기준은 `backend/src/main/resources/db/migration`의 Flyway migration입니다. 루트 `schema.sql`, `init_db.sql`, `test_data.sql`은 사용하거나 추적하지 않습니다. JPA는 `ddl-auto=validate`, SQL init은 비활성화되어 있습니다.

### 4. Frontend와 Admin

```bash
cd frontend
npm ci
cp src/secrets.example.js src/secrets.js
npx expo start --web --port 8081 --host localhost
```

```bash
cd admin
npm ci
npm run dev
```

## 외부 API 기본 정책

`.env.example`에서 Recipe Agent, source discovery, YouTube, MFDS 의약품, RxNorm, openFDA live 연동은 모두 `false`입니다. API key가 비어 있으면 해당 공급자를 사용할 수 없습니다. DuckDuckGo 검색과 로컬 Ollama는 별도 유료 키 없이 사용할 수 있지만 네트워크·로컬 모델 상태에 영향을 받습니다. PortOne은 결제 검증 요청이 들어올 때만 호출됩니다.

외부 연동을 켜기 전에는 이용약관, 호출 제한, 개인정보 전달 범위, timeout/fallback, 운영 로그를 별도로 검토하세요.

## 테스트와 CI 재현

Backend 전체 테스트:

```bash
cd backend
mvn -q clean test
```

Admin:

```bash
cd admin
npm ci
npm run build
npm audit --audit-level=moderate
```

Frontend web export:

```bash
cd frontend
npm ci
cp src/secrets.example.js src/secrets.js
npx expo export --platform web --output-dir dist
npm audit --audit-level=high
```

Compose와 이미지:

```bash
docker compose --env-file .env.example -f docker-compose.prod.yml config
docker build -t salus-backend:local-check ./backend
docker build --build-arg VITE_API_BASE_URL=/api -t salus-admin:local-check ./admin
```

전체 스택은 실제 비밀값을 채운 `.env`로만 실행하세요.

```bash
docker compose --env-file .env -f docker-compose.prod.yml up -d --build
```

## 주요 PR과 브랜치 맥락

- [PR #15 — Recipe Agent](https://github.com/smin0111/Salus/pull/15)
- [PR #16 — 서비스 고도화](https://github.com/smin0111/Salus/pull/16)
- [PR #17 — Frontend renewal (draft)](https://github.com/smin0111/Salus/pull/17)

PR #17은 PR #16의 head branch를 base로 둔 stacked PR입니다. 리뷰·merge 전에 각 PR의 base와 CI 상태를 다시 확인해야 합니다.

## 평가와 현재 한계

- fixture 기반 Recipe Agent 평가는 동일 입력에 대한 회귀를 잡는 도구입니다. 실제 외부 API 품질, 최신 의약품 정보, 임상적 안전성을 증명하지 않습니다.
- 생성 레시피는 구조·근거·금지 재료 검증을 거치지만 모든 식품명 동의어, 교차오염, 개인별 복약 상황을 완전하게 판정하지 못합니다.
- 외부 검색과 로컬 LLM은 네트워크, 모델 설치, 공급자 응답 형식에 따라 실패할 수 있습니다.
- 관리자 전용 로그인·토큰 강제 폐기·권한 변경 감사 UI는 아직 없습니다.
- Recipe Agent 패키지의 일부 외부 API adapter와 domain 집합 파일은 여전히 크며 provider별 분리가 후속 과제입니다.

비밀정보와 민감 데이터 처리 원칙은 [SECURITY.md](SECURITY.md)를 참고하세요.
