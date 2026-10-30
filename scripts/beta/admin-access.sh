#!/usr/bin/env bash
# 관리자 화면 접근 모드를 바꾸고, 실제 접근 결과를 검사해 현재 모드를 출력합니다.
#
#   scripts/beta/admin-access.sh status     실제 바인딩·Tailscale 상태로 현재 모드 출력(바꾸지 않음)
#   scripts/beta/admin-access.sh anywhere   서버 본체 + 관리자 전용 외부 주소 https://<Funnel 주소>:8443
#   scripts/beta/admin-access.sh home       서버 본체 + 집 LAN HTTPS(https://<LAN 주소>:8444)만. 8443 외부 경로는 닫음
#   scripts/beta/admin-access.sh restore    마지막으로 성공한 모드를 다시 적용(기록이 없으면 anywhere).
#                                           마지막 전환이 실패했거나 중단됐으면 아무것도 열지 않고 실패합니다.
#
# 이미지를 다시 빌드하지 않습니다. admin 컨테이너 바인딩과 Tailscale 8443만 바꾸며, 사용자 웹(443)은 건드리지 않습니다.
# 어떤 실패에서도 더 넓게 열지 않습니다: home 사전 검사 실패는 기존 모드 유지, 외부 경로를 닫은 뒤 실패는 닫힌 상태 유지,
# anywhere에서 8443을 연 뒤 실패·중단은 8443을 다시 닫습니다. Tailscale 상태를 읽지 못하면 '확인 불가'로 실패합니다.
# 관리자 로그인·TOTP·역할 검사는 모드와 상관없이 그대로입니다. macOS 기본 bash 3.2에서 동작해야 합니다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="${SALUS_ENV_FILE:-.env.beta}"
PROJECT="${SALUS_COMPOSE_PROJECT:-salus-beta}"
STATE_DIR="${SALUS_ADMIN_STATE_DIR:-$HOME/salus-beta-admin}"
DNS_RESOLVER="${SALUS_PUBLIC_DNS_RESOLVER:-1.1.1.1}"
MODE_FILE="${STATE_DIR}/mode"
LOCK_DIR="${STATE_DIR}/lock"
# Funnel이 받는 포트는 443·8443·10000뿐이고 443은 사용자 웹이므로, 관리자 외부 경로는 8443으로 고정합니다.
FUNNEL_PORT=8443

LOCK_OWNED=0
OPENED_8443=0   # 이번 실행이 8443을 열었고 아직 성공으로 확정하지 않았으면 1. 비정상 종료 시 닫습니다.

say() { echo "$*"; }
fail() { echo "[실패] $*" >&2; exit 1; }
env_value() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true; }

TS="${SALUS_TAILSCALE_BIN:-$(command -v tailscale || true)}"
if [ -z "$TS" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
    TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
fi

load_config() {
    [ -f "$ENV_FILE" ] || fail "${ENV_FILE} 이 없습니다."
    [ -n "$TS" ] || fail "tailscale 명령을 찾을 수 없습니다."
    command -v jq >/dev/null || fail "jq 가 필요합니다."
    ADMIN_PORT="$(env_value ADMIN_PORT)"; ADMIN_PORT="${ADMIN_PORT:-8081}"
    WEB_PORT="$(env_value WEB_PORT)"; WEB_PORT="${WEB_PORT:-8090}"
    LAN_IP="$(env_value ADMIN_LAN_BIND_ADDRESS)"
    LAN_PORT="$(env_value ADMIN_LAN_HTTPS_PORT)"; LAN_PORT="${LAN_PORT:-8444}"
    TLS_DIR="$(env_value ADMIN_LAN_TLS_DIR)"; TLS_DIR="${TLS_DIR/#\~/$HOME}"
    CA_CRT="$(dirname "${TLS_DIR:-/nonexistent/tls}")/ca/ca.crt"
    CORS="$(env_value APP_CORS_ALLOWED_ORIGINS)"
    HOST="$("$TS" status --json 2>/dev/null | jq -r '.Self.DNSName // empty' 2>/dev/null | sed 's/\.$//' || true)"
    TS_IP="$("$TS" ip -4 2>/dev/null | head -1 || true)"
    [ -n "$HOST" ] || fail "Tailscale 주소를 읽지 못했습니다(Tailscale 실행·로그인 상태 확인)."
}

# ---------- 종료 처리 ----------
on_exit() {
    local code=$?
    if [ "$OPENED_8443" = 1 ]; then
        echo "[정리] 비정상 종료로 관리자 8443을 닫습니다." >&2
        if [ "$(close_8443_quiet)" = closed ]; then
            echo "[정리] 8443을 닫았습니다." >&2
        else
            echo "[주의] 8443을 닫았는지 확인하지 못했습니다. tailscale serve status 로 직접 확인하세요." >&2
        fi
        [ "$code" = 0 ] && code=1
    fi
    release_lock
    exit "$code"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# ---------- 잠금: 전환 명령이 동시에 돌지 않게 합니다 ----------
# mkdir가 성공한 실행만 잠금을 가집니다. 이미 있는 잠금은 주인을 확실히 알 수 없으므로 자동으로 지우지 않습니다.
acquire_lock() {
    mkdir -p "$STATE_DIR"
    chmod 700 "$STATE_DIR"
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        local pid
        pid="$(cat "${LOCK_DIR}/pid" 2>/dev/null || echo '알 수 없음')"
        fail "다른 전환이 진행 중이거나 이전 전환이 비정상 종료됐습니다(잠금 pid: ${pid}). 실행 중인 전환이 없으면 ${LOCK_DIR} 를 직접 지운 뒤 다시 실행하세요."
    fi
    LOCK_OWNED=1
    echo $$ > "${LOCK_DIR}/pid"
}

release_lock() {
    [ "$LOCK_OWNED" = 1 ] || return 0
    if [ "$(cat "${LOCK_DIR}/pid" 2>/dev/null || true)" = "$$" ]; then
        rm -f "${LOCK_DIR}/pid"
        rmdir "$LOCK_DIR" 2>/dev/null || true
    fi
    LOCK_OWNED=0
}

# ---------- 모드 기록 ----------
# anywhere | home = 마지막으로 성공한 모드. pending:<모드> = 전환 중(중단되면 그대로 남음). failed:<모드> = 전환 실패.
saved_mode() { cat "$MODE_FILE" 2>/dev/null || echo '기록 없음'; }
record_mode() { echo "$1" > "${MODE_FILE}.tmp" && mv "${MODE_FILE}.tmp" "$MODE_FILE"; }

# ---------- Tailscale 8443 ----------
# 설정 JSON. 명령이 실패하거나 JSON 객체가 아니면 실패(확인 불가)합니다. 빈 설정을 '{}'로 꾸며 내지 않습니다.
serve_json() {
    local out
    out="$("$TS" serve status --json 2>/dev/null)" || return 1
    printf '%s' "$out" | jq -e -c 'if type == "object" then . else error("not object") end' 2>/dev/null
}

# 사용자 웹(443) 설정 조각. 읽지 못하면 'unknown'.
snapshot_443() {
    local j
    j="$(serve_json)" || { echo unknown; return 0; }
    printf '%s' "$j" | jq -S -c --arg w "${HOST}:443" '{tcp: (.TCP["443"] // null), web: ((.Web // {})[$w] // null), funnel: ((.AllowFunnel // {})[$w] // null)}'
}

# 8443 상태: present(Serve 전달이나 Funnel 공개가 하나라도 남음) | absent | unknown(읽지 못함)
state_8443() {
    local j
    j="$(serve_json)" || { echo unknown; return 0; }
    if printf '%s' "$j" | jq -e --arg p "$FUNNEL_PORT" '
        ((.TCP // {})[$p] != null)
        or ((.Web // {}) | keys | any(endswith(":" + $p)))
        or ((.AllowFunnel // {}) | keys | any(endswith(":" + $p)))' >/dev/null; then
        echo present
    else
        echo absent
    fi
}

# 관리자 Funnel 상태: on(공개 + 127.0.0.1:ADMIN_PORT로 전달) | off | unknown
funnel_8443_state() {
    local j
    j="$(serve_json)" || { echo unknown; return 0; }
    if printf '%s' "$j" | jq -e --arg w "${HOST}:${FUNNEL_PORT}" --arg t "http://127.0.0.1:${ADMIN_PORT}" '
        ((.AllowFunnel // {})[$w] == true) and (((.Web // {})[$w].Handlers["/"].Proxy) == $t)' >/dev/null; then
        echo on
    else
        echo off
    fi
}

# 8443을 닫고 결과를 출력합니다: closed | present | unknown. 사용자 웹(443)은 건드리지 않습니다.
close_8443_quiet() {
    "$TS" funnel --https="$FUNNEL_PORT" off >/dev/null 2>&1 || true
    # Funnel 공개만 꺼지고 Serve 전달(테일넷 안 접근)이 남는 경우까지 지웁니다.
    if [ "$(state_8443)" != absent ]; then
        "$TS" serve --https="$FUNNEL_PORT" off >/dev/null 2>&1 || true
    fi
    case "$(state_8443)" in
        absent) echo closed ;;
        present) echo present ;;
        *) echo unknown ;;
    esac
}

open_8443() {
    OPENED_8443=1
    "$TS" funnel --bg --yes --https="$FUNNEL_PORT" "http://127.0.0.1:${ADMIN_PORT}" >/dev/null
}

# ---------- admin 컨테이너 ----------
admin_container() {
    docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" --filter "label=com.docker.compose.service=admin" 2>/dev/null | head -1 || true
}

# 바인딩 목록(정렬, 쉼표 구분). 예: 127.0.0.1:8081,172.30.1.82:8444
admin_bindings() {
    local id
    id="$(admin_container)"
    if [ -z "$id" ]; then echo "(admin 없음)"; return 0; fi
    docker port "$id" 2>/dev/null | awk '{print $3}' | sort -u | paste -sd, - || true
}

home_bindings() { printf '%s\n' "127.0.0.1:${ADMIN_PORT}" "${LAN_IP}:${LAN_PORT}" | sort | paste -sd, -; }

recreate_admin() {  # $1 = anywhere | home
    local id status
    if [ "$1" = home ]; then
        docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.prod.yml -f docker-compose.admin-home.yml \
            up -d --no-deps --no-build admin >/dev/null 2>&1 || return 1
    else
        docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.prod.yml \
            up -d --no-deps --no-build admin >/dev/null 2>&1 || return 1
    fi
    for _ in $(seq 1 45); do
        id="$(admin_container)"
        status="$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || true)"
        if [ "$status" = healthy ]; then
            [ "$1" = home ] && reload_lan_cert_if_changed "$id"
            return 0
        fi
        sleep 2
    done
    return 1
}

# LAN 인증서 지문(SHA-256). 파일과 실제로 내보내는 인증서를 비교합니다. 읽지 못하면 빈 값입니다.
lan_cert_fp_file() { openssl x509 -in "${TLS_DIR}/server.crt" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 || true; }
lan_cert_fp_served() {
    openssl s_client -connect "${LAN_IP}:${LAN_PORT}" </dev/null 2>/dev/null \
        | openssl x509 -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 || true
}

# 이미 home이면 compose가 컨테이너를 그대로 두므로, 인증서를 갱신한 뒤에는 nginx가 예전 인증서를 계속 씁니다.
# 내보내는 인증서가 파일과 다를 때만 nginx 설정을 다시 읽게 합니다. 결과는 check_home이 다시 확인합니다.
reload_lan_cert_if_changed() {
    local want
    want="$(lan_cert_fp_file)"
    [ -n "$want" ] && [ "$(lan_cert_fp_served)" != "$want" ] || return 0
    say "home 적용: LAN 인증서가 바뀌어 admin nginx가 새 인증서를 읽게 합니다."
    docker exec "$1" nginx -s reload >/dev/null 2>&1 || return 0
    for _ in $(seq 1 10); do
        [ "$(lan_cert_fp_served)" = "$want" ] && return 0
        sleep 1
    done
}

# ---------- 접근 검사 ----------
# 연결 자체가 안 되면 000입니다.
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 6 "$@" 2>/dev/null || true; }
public_ip() { dig +short +time=3 +tries=1 "@${DNS_RESOLVER}" "$HOST" A 2>/dev/null | grep -E '^[0-9.]+$' | head -1 || true; }

FAILS=0
expect() {  # 설명, 기대, 실제
    if [ "$2" = "$3" ]; then
        say "  ok   $1: $3"
    else
        say "  실패 $1: 기대 $2, 실제 $3"
        FAILS=$((FAILS + 1))
    fi
}

check_common() {
    expect "서버 본체 관리자 화면 http://127.0.0.1:${ADMIN_PORT}" 200 "$(http_code "http://127.0.0.1:${ADMIN_PORT}/")"
    expect "서버 본체 관리자 API(로그인 전)" 401 "$(http_code "http://127.0.0.1:${ADMIN_PORT}/api/admin/auth/me")"
    if [ -n "$TS_IP" ]; then
        expect "Tailscale IP 직접 ${TS_IP}:${ADMIN_PORT}" 000 "$(http_code "http://${TS_IP}:${ADMIN_PORT}/")"
    else
        expect "Tailscale IP 조회" 성공 실패
    fi
    if [ -n "$LAN_IP" ]; then
        expect "LAN 평문 http://${LAN_IP}:${ADMIN_PORT}" 000 "$(http_code "http://${LAN_IP}:${ADMIN_PORT}/")"
    fi
    expect "사용자 웹으로 관리자 API 전달" 404 "$(http_code "http://127.0.0.1:${WEB_PORT}/api/admin/auth/me")"
}

check_anywhere() {
    local pip url="https://${HOST}:${FUNNEL_PORT}"
    check_common
    expect "admin 바인딩" "127.0.0.1:${ADMIN_PORT}" "$(admin_bindings)"
    expect "8443 Funnel 설정(127.0.0.1:${ADMIN_PORT} 로 전달)" on "$(funnel_8443_state)"
    expect "테일넷 경로 ${url}" 200 "$(http_code "${url}/")"
    pip="$(public_ip)"
    if [ -n "$pip" ]; then
        expect "공개 경로 ${url} (공개 DNS ${pip})" 200 "$(http_code --resolve "${HOST}:${FUNNEL_PORT}:${pip}" "${url}/")"
        expect "공개 경로 관리자 API(로그인 전)" 401 "$(http_code --resolve "${HOST}:${FUNNEL_PORT}:${pip}" "${url}/api/admin/auth/me")"
        expect "공개 경로 CORS 사전 확인(${url})" 200 "$(http_code --resolve "${HOST}:${FUNNEL_PORT}:${pip}" -X OPTIONS \
            -H "Origin: ${url}" -H 'Access-Control-Request-Method: POST' "${url}/api/admin/auth/login")"
    else
        expect "공개 DNS 조회(${DNS_RESOLVER})" 성공 "실패(공개 경로 확인 불가)"
    fi
}

check_home() {
    local pip lan="https://${LAN_IP}:${LAN_PORT}"
    check_common
    expect "admin 바인딩" "$(home_bindings)" "$(admin_bindings)"
    expect "8443 Serve·Funnel 설정" absent "$(state_8443)"
    expect "LAN HTTPS 인증서(파일과 같은지)" "$(lan_cert_fp_file)" "$(lan_cert_fp_served)"
    expect "LAN HTTPS 관리자 화면 ${lan}" 200 "$(http_code --cacert "$CA_CRT" "${lan}/")"
    expect "LAN HTTPS 관리자 API(로그인 전)" 401 "$(http_code --cacert "$CA_CRT" "${lan}/api/admin/auth/me")"
    expect "LAN HTTPS CORS 사전 확인(${lan})" 200 "$(http_code --cacert "$CA_CRT" -X OPTIONS \
        -H "Origin: ${lan}" -H 'Access-Control-Request-Method: POST' "${lan}/api/admin/auth/login")"
    if [ -n "$TS_IP" ]; then
        expect "Tailscale IP 직접 ${TS_IP}:${LAN_PORT}" 000 "$(http_code -k "https://${TS_IP}:${LAN_PORT}/")"
    fi
    expect "테일넷 경로 https://${HOST}:${FUNNEL_PORT}" 000 "$(http_code "https://${HOST}:${FUNNEL_PORT}/")"
    pip="$(public_ip)"
    if [ -n "$pip" ]; then
        expect "공개 경로 https://${HOST}:${FUNNEL_PORT} (공개 DNS ${pip})" 000 \
            "$(http_code --resolve "${HOST}:${FUNNEL_PORT}:${pip}" "https://${HOST}:${FUNNEL_PORT}/")"
    else
        expect "공개 DNS 조회(${DNS_RESOLVER})" 성공 "실패(공개 경로 확인 불가)"
    fi
}

# ---------- home 사전 검사(아무것도 바꾸지 않음) ----------
# 인증서와 개인 키의 공개 키가 같은지 비교합니다. 어느 쪽이든 읽지 못하면 불일치로 봅니다.
key_matches_cert() {
    local cert_pub key_pub
    cert_pub="$(openssl x509 -in "$1" -noout -pubkey 2>/dev/null)" || return 1
    key_pub="$(openssl pkey -in "$2" -pubout 2>/dev/null)" || return 1
    [ -n "$cert_pub" ] && [ "$cert_pub" = "$key_pub" ]
}

precheck_home() {
    local errs=0
    err() { echo "  - $*" >&2; errs=$((errs + 1)); }
    if [[ ! "$LAN_IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
        err "ADMIN_LAN_BIND_ADDRESS 가 IPv4 주소 하나가 아닙니다: '${LAN_IP}'"
    elif [[ ! "$LAN_IP" =~ ^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.) ]]; then
        err "ADMIN_LAN_BIND_ADDRESS 가 사설 주소가 아닙니다: ${LAN_IP}"
    elif ! ifconfig | grep -q "inet ${LAN_IP} "; then
        err "이 맥에 ${LAN_IP} 주소가 없습니다(공유기가 다른 주소를 줬을 수 있음)."
    fi
    if [[ ! "$LAN_PORT" =~ ^[0-9]+$ ]] || [ "$LAN_PORT" = "$ADMIN_PORT" ]; then
        err "ADMIN_LAN_HTTPS_PORT 값이 잘못됐습니다: ${LAN_PORT}"
    fi
    if [ -z "$TLS_DIR" ] || [ ! -f "${TLS_DIR}/server.crt" ] || [ ! -f "${TLS_DIR}/server.key" ] || [ ! -f "$CA_CRT" ]; then
        err "LAN 인증서가 없습니다. 먼저 scripts/beta/admin-lan-cert.sh 를 실행하세요."
    elif ! openssl verify -CAfile "$CA_CRT" "${TLS_DIR}/server.crt" >/dev/null 2>&1 \
        || ! openssl x509 -in "${TLS_DIR}/server.crt" -noout -checkend 86400 >/dev/null 2>&1 \
        || ! openssl x509 -in "${TLS_DIR}/server.crt" -noout -ext subjectAltName 2>/dev/null | grep -q "IP Address:${LAN_IP}\$"; then
        err "LAN 인증서가 ${LAN_IP} 용이 아니거나 만료(임박)됐습니다. scripts/beta/admin-lan-cert.sh 를 다시 실행하세요."
    elif ! key_matches_cert "${TLS_DIR}/server.crt" "${TLS_DIR}/server.key"; then
        # 키가 틀리면 nginx가 뜨지 못해, 8443을 닫은 뒤 관리자 접속이 모두 끊깁니다.
        err "LAN 개인 키를 읽지 못했거나 인증서와 짝이 맞지 않습니다. scripts/beta/admin-lan-cert.sh 를 다시 실행하세요."
    fi
    case ",${CORS// /}," in
        *",https://${LAN_IP}:${LAN_PORT},"*) ;;
        *) err "APP_CORS_ALLOWED_ORIGINS 에 https://${LAN_IP}:${LAN_PORT} 가 없습니다(추가 후 backend 재시작 필요)." ;;
    esac
    if [ "$(state_8443)" = unknown ]; then
        err "Tailscale 설정을 읽지 못했습니다(확인 불가)."
    fi
    [ "$errs" = 0 ]
}

# ---------- 상태 ----------
observed_mode() {
    local b s8 fs
    b="$(admin_bindings)"
    s8="$(state_8443)"
    fs="$(funnel_8443_state)"
    if [ "$s8" = unknown ] || [ "$fs" = unknown ]; then
        echo "확인 불가(Tailscale 상태를 읽지 못함)"
    elif [ -z "$(admin_container)" ]; then
        if [ "$s8" = present ]; then echo "불일치(admin 꺼짐, 8443 설정 남음)"; else echo "admin 꺼짐"; fi
    elif [ "$b" = "127.0.0.1:${ADMIN_PORT}" ]; then
        if [ "$fs" = on ]; then echo anywhere
        elif [ "$s8" = present ]; then echo 불일치
        else echo 닫힘; fi
    elif [ -n "$LAN_IP" ] && [ "$b" = "$(home_bindings)" ]; then
        if [ "$s8" = absent ]; then echo home; else echo 불일치; fi
    else
        echo 불일치
    fi
}

print_status() {
    local saved observed s8
    saved="$(saved_mode)"
    observed="$(observed_mode)"
    s8="$(state_8443)"
    say "현재 모드: ${observed} (마지막 기록: ${saved})"
    say "  admin 바인딩: $(admin_bindings)"
    case "$(funnel_8443_state):${s8}" in
        on:*) say "  8443 Serve·Funnel: 켜짐 → http://127.0.0.1:${ADMIN_PORT}" ;;
        *:present) say "  8443 Serve·Funnel: 다른 설정 남아 있음" ;;
        *:absent) say "  8443 Serve·Funnel: 없음" ;;
        *) say "  8443 Serve·Funnel: 확인 불가" ;;
    esac
    case "$observed" in
        anywhere) say "  접속: 서버 본체 http://127.0.0.1:${ADMIN_PORT}, 외부 https://${HOST}:${FUNNEL_PORT}" ;;
        home) say "  접속: 서버 본체 http://127.0.0.1:${ADMIN_PORT}, 집 LAN https://${LAN_IP}:${LAN_PORT}" ;;
        닫힘) say "  접속: 서버 본체 http://127.0.0.1:${ADMIN_PORT} 만" ;;
    esac
    [ "$observed" = "$saved" ]
}

guard_443() {  # $1 = 전환 전 스냅숏
    local after
    after="$(snapshot_443)"
    if [ "$1" = unknown ] || [ "$after" = unknown ]; then
        expect "사용자 웹(443) Tailscale 설정 확인" 가능 "확인 불가"
    elif [ "$after" != "$1" ]; then
        expect "사용자 웹(443) Tailscale 설정" "전환 전과 같음" "바뀜(tailscale serve status 로 확인)"
    fi
}

# ---------- 전환 ----------
apply_anywhere() {
    local before c
    before="$(snapshot_443)"
    [ "$before" != unknown ] || fail "anywhere 적용 실패 · Tailscale 설정을 읽지 못해 아무것도 바꾸지 않았습니다."
    record_mode pending:anywhere
    say "anywhere 적용: admin을 서버 본체 전용으로 다시 만들고 관리자 8443을 엽니다."
    if ! recreate_admin anywhere; then
        c="$(close_8443_quiet)"
        record_mode failed:anywhere
        fail "anywhere 적용 실패 · admin 컨테이너를 다시 만들지 못했습니다. 8443 닫기: ${c}. 현재 모드: $(observed_mode)"
    fi
    open_8443 || true
    check_anywhere
    guard_443 "$before"
    if [ "$FAILS" -gt 0 ]; then
        c="$(close_8443_quiet)"
        OPENED_8443=0
        record_mode failed:anywhere
        [ "$c" = closed ] || say "  [주의] 8443을 닫았는지 확인하지 못했습니다(${c}). tailscale serve status 로 직접 확인하세요."
        fail "anywhere 적용 실패(${FAILS}건) · 관리자 8443 닫기: ${c}. 현재 모드: $(observed_mode)"
    fi
    # 성공 기록을 남긴 뒤에야 종료 시 8443 정리를 해제합니다(기록 실패·중단이면 trap이 8443을 닫음).
    record_mode anywhere
    OPENED_8443=0
}

apply_home() {
    local before c
    if ! precheck_home; then
        fail "home 적용 실패 · 아무것도 바꾸지 않았고 기존 모드를 유지합니다(현재 모드: $(observed_mode))."
    fi
    before="$(snapshot_443)"
    [ "$before" != unknown ] \
        || fail "home 적용 실패 · Tailscale 443 설정을 읽지 못해 아무것도 바꾸지 않았고 기존 모드를 유지합니다."
    # 여기서부터는 외부 경로를 닫습니다. 중간에 멈춰도 restore가 다시 열지 않도록 먼저 기록합니다.
    record_mode pending:home
    say "home 적용: 관리자 8443 외부 경로를 먼저 닫습니다."
    c="$(close_8443_quiet)"
    if [ "$c" != closed ]; then
        record_mode failed:home
        fail "home 적용 실패 · 8443을 닫았는지 확인하지 못했습니다(${c}). tailscale serve status 로 직접 확인하세요."
    fi
    say "home 적용: admin을 서버 본체 + 집 LAN HTTPS로 다시 만듭니다."
    if ! recreate_admin home; then
        record_mode failed:home
        fail "home 적용 실패 · admin 컨테이너를 다시 만들지 못했습니다. 외부 경로는 닫힌 상태입니다. 현재 모드: $(observed_mode)"
    fi
    check_home
    guard_443 "$before"
    if [ "$FAILS" -gt 0 ]; then
        if [ "$(state_8443)" != absent ]; then close_8443_quiet >/dev/null; fi
        record_mode failed:home
        fail "home 검증 실패(${FAILS}건) · 외부 경로는 닫힌 상태로 둡니다. 현재 모드: $(observed_mode)"
    fi
    record_mode home
}

CMD="${1:-status}"
case "$CMD" in
    status)
        load_config
        print_status ;;
    anywhere|home|restore)
        load_config
        acquire_lock
        if [ "$CMD" = restore ]; then
            CMD="$(saved_mode)"
            [ "$CMD" = '기록 없음' ] && CMD=anywhere
            case "$CMD" in
                anywhere|home) say "마지막 모드(${CMD})를 다시 적용합니다." ;;
                *) fail "마지막 전환 기록이 '${CMD}' 입니다. 자동으로 다시 열지 않습니다. 확인 후 anywhere 또는 home 을 직접 실행하세요. 현재 모드: $(observed_mode)" ;;
            esac
        fi
        "apply_${CMD}"
        say "적용 완료."
        print_status ;;
    *)
        echo "사용법: $0 status|anywhere|home|restore" >&2
        exit 2 ;;
esac
