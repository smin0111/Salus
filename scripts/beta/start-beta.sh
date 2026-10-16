#!/usr/bin/env bash
# 맥북 베타 서버를 켭니다: Ollama 확인 -> Docker(prod compose) -> 상태 확인 -> Tailscale Funnel -> 잠자기 방지.
# 사용법: scripts/beta/start-beta.sh   (끄기: scripts/beta/stop-beta.sh)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENV_FILE=".env.beta"
PROJECT="salus-beta"
CAFFEINATE_PID_FILE="${TMPDIR:-/tmp}/salus-beta-caffeinate.pid"
REQUIRED_MODELS=("qwen3:8b" "gemma3:4b")

fail() { echo "[중단] $*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || fail "$ENV_FILE 이 없습니다. scripts/beta/beta.env.example 을 복사해 값을 채우세요."
for key in MYSQL_PASSWORD MYSQL_ROOT_PASSWORD JWT_SECRET APP_CORS_ALLOWED_ORIGINS; do
    grep -Eq "^${key}=.+" "$ENV_FILE" || fail "$ENV_FILE 의 ${key} 값이 비어 있습니다."
done
grep -q "salus.example.ts.net" "$ENV_FILE" && echo "[주의] APP_CORS_ALLOWED_ORIGINS 에 예시 주소가 남아 있습니다. Funnel 주소로 바꾸세요."
[ -f searxng/settings.yml ] || fail "searxng/settings.yml 이 없습니다."
[ -f frontend/src/secrets.js ] || fail "frontend/src/secrets.js 가 없습니다(웹 빌드에 필요)."

WEB_PORT="$(grep -E '^WEB_PORT=' "$ENV_FILE" | cut -d= -f2)"
WEB_PORT="${WEB_PORT:-8090}"

# 개발 서버(백엔드 8080, Expo 8081)가 켜져 있으면 포트가 겹칩니다.
for port in 8080 8081 "$WEB_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $1}' | grep -vq "com.docke"; then
        fail "${port} 포트를 다른 프로그램이 쓰고 있습니다(개발 서버를 먼저 끄세요): $(lsof -nP -iTCP:"$port" -sTCP:LISTEN | awk 'NR==2 {print $1}')"
    fi
done

docker info >/dev/null 2>&1 || fail "Docker Desktop 이 켜져 있지 않습니다."

# 1. Ollama: Docker 밖(맥)에서 돌려야 맥북 GPU를 씁니다.
if ! curl -sf http://localhost:11434/api/tags >/dev/null; then
    echo "Ollama 를 켭니다..."
    if [ -d /Applications/Ollama.app ]; then open -a Ollama; else nohup ollama serve >/dev/null 2>&1 & fi
    for _ in $(seq 1 30); do curl -sf http://localhost:11434/api/tags >/dev/null && break; sleep 1; done
    curl -sf http://localhost:11434/api/tags >/dev/null || fail "Ollama 가 응답하지 않습니다."
fi
TAGS="$(curl -sf http://localhost:11434/api/tags)"
for model in "${REQUIRED_MODELS[@]}"; do
    echo "$TAGS" | grep -q "\"$model\"" || fail "Ollama 모델 $model 이 없습니다: ollama pull $model"
done

# 2. 서비스 실행(처음이거나 코드가 바뀌었으면 이미지를 다시 만듭니다).
echo "서비스를 시작합니다(처음에는 빌드에 몇 분 걸립니다)..."
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.prod.yml up -d --build

# 3. 웹 화면과 /api 경로가 응답할 때까지 기다립니다.
echo "응답을 기다립니다..."
for _ in $(seq 1 90); do
    if curl -sf "http://127.0.0.1:${WEB_PORT}/" >/dev/null \
        && curl -sf -X POST "http://127.0.0.1:${WEB_PORT}/api/auth/nonce" >/dev/null; then
        READY=1; break
    fi
    sleep 2
done
[ "${READY:-0}" = 1 ] || fail "3분 안에 응답이 없습니다. 확인: docker compose -p $PROJECT logs backend --tail 100"
echo "로컬 확인: http://127.0.0.1:${WEB_PORT}"

# 4. 외부 공개(Tailscale Funnel).
TS="$(command -v tailscale || true)"
[ -z "$TS" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
if [ -n "$TS" ]; then
    echo "외부 공개를 켭니다. 처음이면 Funnel 허용 링크가 나오니 브라우저에서 허용하면 이어서 진행됩니다."
    "$TS" funnel --bg "$WEB_PORT"
    "$TS" funnel status
else
    echo "[주의] Tailscale 이 없어 외부 공개는 건너뜁니다."
fi

# 5. 잠자기 방지(덮개를 닫으면 전원 연결 여부와 상관없이 잠들 수 있으니 열어 두세요).
if [ -f "$CAFFEINATE_PID_FILE" ] && kill -0 "$(cat "$CAFFEINATE_PID_FILE")" 2>/dev/null; then
    :
else
    nohup caffeinate -dis >/dev/null 2>&1 &
    echo $! > "$CAFFEINATE_PID_FILE"
fi

echo "베타 서버가 켜졌습니다. 끄기: scripts/beta/stop-beta.sh"
