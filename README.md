# Salus

Salus는 사용자의 건강정보, 알레르기, 냉장고 재료를 참고해 식단과 레시피를 추천하는 개인 맞춤형 AI 식탁 도우미입니다.

## 프로젝트 구성

- `backend`: Spring Boot API 서버
- `frontend`: Expo/React Native 사용자 앱
- `admin`: Vite/React 관리자 앱
- `.github`: CI, 이슈 템플릿, PR 템플릿, Dependabot 설정

## 사전 준비

- Java 17
- Maven
- Node.js 20 권장
- MySQL 8.0 권장
- Docker Desktop 또는 Redis 직접 설치

백엔드는 기본적으로 로컬 MySQL의 `mychefai` 데이터베이스와 Redis `localhost:6379`에 연결합니다.
현재 Spring Boot 3.2.x와 Flyway 9.22.x 조합은 MySQL 8.0 계열을 기준으로 두는 것이 가장 안전합니다.

## 로컬 실행

### 백엔드

처음 실행할 때는 로컬 전용 secret 파일을 먼저 준비합니다.

```bash
cp backend/src/main/resources/application-secret.example.properties backend/src/main/resources/application-secret.properties
```

`application-secret.properties`에는 실제 로컬 DB 비밀번호, JWT secret, 외부 API 키를 넣습니다. 이 파일은 절대 커밋하지 않습니다.

Redis는 아래 명령으로 실행할 수 있습니다.

```bash
docker compose up -d redis
```

MySQL에는 `mychefai` 데이터베이스가 있어야 합니다.

```bash
cd backend
mvn spring-boot:run
```

기본 주소는 `http://localhost:8080`입니다.

### 프론트엔드

처음 실행할 때는 앱에서 참조하는 로컬 전용 설정 파일을 준비합니다.

```bash
cp frontend/src/secrets.example.js frontend/src/secrets.js
```

실제 소셜 로그인 키는 `secrets.js`에 넣고, 이 파일은 커밋하지 않습니다.

```bash
cd frontend
npm install
npx expo start --web --port 8081 --host localhost
```

기본 주소는 `http://localhost:8081`입니다.

### 어드민

```bash
cd admin
npm install
npm run dev
```

어드민은 기본적으로 `http://localhost:8080/api`를 백엔드 API로 사용합니다.
관리자 콘솔은 현재 `ADMIN` 권한 사용자의 JWT를 입력해 접속합니다. 토큰은 일반 비밀번호처럼 공유하거나 문서에 남기지 말고, 자세한 운영 기준은 [SECURITY.md](SECURITY.md)의 관리자 JWT 운영 기준을 따릅니다.

### Docker 배포와 Ollama

운영 또는 운영과 비슷한 환경에서는 먼저 `.env.example`을 복사해 `.env`를 만들고 실제 값을 채웁니다.

```bash
cp .env.example .env
```

특히 `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `JWT_SECRET`, `APP_CORS_ALLOWED_ORIGINS`, `IAMPORT_API_KEY`, `IAMPORT_API_SECRET`은 예시값 그대로 두면 안 됩니다.
`JWT_SECRET`은 최소 32바이트 이상의 긴 난수 문자열을 사용합니다.
`APP_TIME_ZONE`은 하루 통계와 날짜 기준 기능의 운영 시간대입니다. 한국 서비스 기준이면 `Asia/Seoul`을 유지합니다.

배포 전에 compose 설정과 이미지를 먼저 검증합니다.

```bash
docker compose --env-file .env -f docker-compose.prod.yml config
docker build -t salus-backend:local-check ./backend
docker build --build-arg VITE_API_BASE_URL=/api -t salus-admin:local-check ./admin
```

문제가 없으면 아래처럼 실행합니다.

```bash
docker compose --env-file .env -f docker-compose.prod.yml up -d --build
```

`docker-compose.prod.yml`로 백엔드를 컨테이너에서 실행하면 컨테이너 안의 `localhost`는 호스트 PC가 아니라 백엔드 컨테이너 자신을 뜻합니다.
호스트에서 Ollama를 실행하는 경우 `.env`의 `OLLAMA_PRIMARY_URL`, `OLLAMA_SECONDARY_URL`을 `http://host.docker.internal:11434/api/chat`처럼 설정합니다.
별도 Ollama 컨테이너나 외부 서버를 쓴다면 해당 서비스 주소로 바꿉니다.

## 검증 명령어

PR을 만들기 전 최소한 아래 명령어를 확인합니다.

```bash
cd backend
mvn test
```

```bash
cd admin
npm run build
npm audit --audit-level=moderate
```

```bash
cd frontend
npx expo export --platform web --output-dir dist
npm audit --audit-level=high
```

## 운영 준비 체크

- 비밀키는 `.env`, `application-secret.properties`, GitHub Secrets 같은 안전한 위치에서만 관리합니다.
- 새 환경변수는 `.env.example` 또는 예시 설정 파일에 문서화합니다.
- 건강정보, 알레르기, 결제, 인증 변경은 PR에 위험 영역으로 표시합니다.
- Docker 배포 전 `docker-compose.prod.yml`과 `backend/Dockerfile`, `admin/Dockerfile` 빌드를 확인합니다.
- 보안 신고와 민감정보 기준은 [SECURITY.md](SECURITY.md)를 따릅니다.

## GitHub 관리

- 이슈는 `.github/ISSUE_TEMPLATE`의 템플릿을 사용합니다.
- PR은 `.github/pull_request_template.md` 체크리스트를 채워 리뷰합니다.
- 의존성 업데이트는 `.github/dependabot.yml`이 매주 확인합니다.
