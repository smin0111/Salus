#!/usr/bin/env bash
# 24시간 관제 화면용 디스플레이 토큰을 만듭니다.
# - 토큰 원문은 여기서 한 번만 출력됩니다. 관제 화면 등록 주소에만 쓰고 어디에도 저장하지 마세요.
# - 서버에는 해시만 MONITOR_DISPLAY_TOKEN_HASHES에 넣습니다(화면이 여러 대면 쉼표로 이어 붙임).
set -euo pipefail

token="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
hash="$(printf '%s' "$token" | openssl dgst -sha256 -r | cut -d' ' -f1)"

cat <<OUT
[디스플레이 토큰 원문 - 한 번만 표시]
$token

[관제 화면 등록 주소 - 관제 화면 브라우저에서 한 번 접속]
https://<관리자 웹 주소>/monitor#display-token=$token

[서버 환경변수에 추가할 해시]
MONITOR_DISPLAY_TOKEN_HASHES=$hash
OUT
