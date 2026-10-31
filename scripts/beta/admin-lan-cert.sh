#!/usr/bin/env bash
# 집 LAN에서 관리자 화면을 HTTPS로 열 인증서를 만듭니다(home 모드용).
# 집 전용 CA를 한 번 만들고, 그 CA로 LAN 주소(IP) 인증서를 서명합니다. 파일은 저장소 밖(ADMIN_LAN_TLS_DIR의 상위)에 둡니다.
#
# 사용법: scripts/beta/admin-lan-cert.sh            (.env.beta의 ADMIN_LAN_BIND_ADDRESS·ADMIN_LAN_TLS_DIR 사용)
# 만든 뒤 ca.crt를 관리자 접속 기기에 설치해 신뢰해야 경고 없이 열립니다(iPhone: 설치 후 설정 > 일반 > 정보 > 인증서 신뢰 설정).
# CA 개인 키(ca.key)는 이 맥에만 두고 기기로 옮기지 마세요. LAN 주소가 바뀌면 이 스크립트를 다시 실행합니다(CA는 그대로 재사용).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${SALUS_ENV_FILE:-$ROOT/.env.beta}"

fail() { echo "[중단] $*" >&2; exit 1; }
env_value() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true; }

[ -f "$ENV_FILE" ] || fail "$ENV_FILE 이 없습니다."
LAN_IP="$(env_value ADMIN_LAN_BIND_ADDRESS)"
TLS_DIR="$(env_value ADMIN_LAN_TLS_DIR)"
TLS_DIR="${TLS_DIR/#\~/$HOME}"
[[ "$LAN_IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || fail "ADMIN_LAN_BIND_ADDRESS 가 IPv4 주소가 아닙니다: '${LAN_IP}'"
[ -n "$TLS_DIR" ] || fail "ADMIN_LAN_TLS_DIR 가 비어 있습니다."
case "$TLS_DIR" in "$ROOT"*) fail "ADMIN_LAN_TLS_DIR 는 저장소 밖이어야 합니다: $TLS_DIR" ;; esac

CA_DIR="$(dirname "$TLS_DIR")/ca"
umask 077
mkdir -p "$CA_DIR" "$TLS_DIR"

if [ ! -f "$CA_DIR/ca.key" ]; then
    openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
        -keyout "$CA_DIR/ca.key" -out "$CA_DIR/ca.crt" \
        -subj "/CN=Salus Home Admin CA" \
        -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
    chmod 644 "$CA_DIR/ca.crt"
    echo "집 전용 CA를 만들었습니다: $CA_DIR/ca.crt"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/ext.cnf" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:${LAN_IP}
EOF
openssl req -newkey rsa:2048 -nodes -sha256 -keyout "$TMP/server.key" -out "$TMP/server.csr" \
    -subj "/CN=${LAN_IP}" 2>/dev/null
# iOS·macOS는 서버 인증서 유효 기간 825일 이하만 신뢰합니다.
openssl x509 -req -in "$TMP/server.csr" -CA "$CA_DIR/ca.crt" -CAkey "$CA_DIR/ca.key" -CAcreateserial \
    -CAserial "$CA_DIR/ca.srl" -days 825 -sha256 -extfile "$TMP/ext.cnf" -out "$TMP/server.crt" 2>/dev/null

openssl verify -CAfile "$CA_DIR/ca.crt" "$TMP/server.crt" >/dev/null || fail "인증서 검증에 실패했습니다."
mv "$TMP/server.key" "$TLS_DIR/server.key"
mv "$TMP/server.crt" "$TLS_DIR/server.crt"
chmod 600 "$TLS_DIR/server.key"
chmod 644 "$TLS_DIR/server.crt"

echo "LAN 인증서를 만들었습니다: $TLS_DIR/server.crt (IP ${LAN_IP}, 825일)"
echo "기기에 설치할 CA 인증서: $CA_DIR/ca.crt"
echo "home 모드가 켜져 있으면 다시 적용하세요: scripts/beta/admin-access.sh home"
