#!/usr/bin/env bash
# 맥북 베타 서버를 끕니다: Funnel 해제 -> 서비스 종료 -> 잠자기 방지 해제.
# DB 데이터(Docker 볼륨)는 지우지 않으므로 다음에 켜면 그대로 이어집니다. Ollama 는 그대로 둡니다.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENV_FILE=".env.beta"
PROJECT="salus-beta"
CAFFEINATE_PID_FILE="${TMPDIR:-/tmp}/salus-beta-caffeinate.pid"

TS="$(command -v tailscale || true)"
[ -z "$TS" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
if [ -n "$TS" ]; then
    "$TS" funnel reset && echo "외부 공개를 껐습니다."
fi

if [ -f "$ENV_FILE" ]; then
    docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.prod.yml down
else
    docker compose -p "$PROJECT" -f docker-compose.prod.yml down
fi

if [ -f "$CAFFEINATE_PID_FILE" ]; then
    kill "$(cat "$CAFFEINATE_PID_FILE")" 2>/dev/null
    rm -f "$CAFFEINATE_PID_FILE"
fi

echo "베타 서버를 껐습니다."
