#!/usr/bin/env bash
# 예전에 손으로 만든 평문 덤프(.sql)를 backup-mysql.sh와 같은 형식으로 암호화해 옮기고, 확인이 끝난 평문만 지웁니다.
# 평문 덤프에는 건강정보가 그대로 있고, backup-mysql.sh의 보관 기간 정리 대상이 아니라 계속 남았습니다(2026-10-31 점검).
# 사용법: BACKUP_KEY_FILE=~/.salus-backup.key scripts/beta/encrypt-legacy-backups.sh [평문 폴더]
#   평문 폴더 기본값: ~/salus-beta-backups   저장 위치: BACKUP_DIR(기본 ~/salus-backups)
#   DRY_RUN=1이면 무엇을 할지만 보여 주고 아무것도 바꾸지 않습니다.
# 원래 파일의 수정 시각을 그대로 옮겨, backup-mysql.sh의 보관 기간(기본 14일) 정리가 원래 날짜 기준으로 지웁니다.
# 각 파일은 암호화 → 복호화해 원본과 해시가 같은지 확인한 뒤에만 평문을 지웁니다. 하나라도 실패하면 그 파일은 남깁니다.
set -euo pipefail

LEGACY_DIR="${1:-$HOME/salus-beta-backups}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/salus-backups}"
BACKUP_KEY_FILE="${BACKUP_KEY_FILE:-}"
DRY_RUN="${DRY_RUN:-0}"

fail() { echo "[중단] $*" >&2; exit 1; }

[ -d "$LEGACY_DIR" ] || fail "평문 폴더가 없습니다: $LEGACY_DIR"
[ -n "$BACKUP_KEY_FILE" ] && [ -f "$BACKUP_KEY_FILE" ] || fail "BACKUP_KEY_FILE(암호화 키 파일)을 지정하세요."
if [ "$(uname -s)" = "Darwin" ]; then
    KEY_MODE="$(stat -f '%Lp' "$BACKUP_KEY_FILE")"
else
    KEY_MODE="$(stat -c '%a' "$BACKUP_KEY_FILE")"
fi
[ "$KEY_MODE" = "600" ] || fail "키 파일 권한은 600이어야 합니다: chmod 600 $BACKUP_KEY_FILE"

umask 077
mkdir -p "$BACKUP_DIR"
shopt -s nullglob
files=("$LEGACY_DIR"/*.sql)
[ "${#files[@]}" -gt 0 ] || { echo "[완료] 평문 덤프가 없습니다."; exit 0; }

for src in "${files[@]}"; do
    base="$(basename "$src" .sql)"
    out="$BACKUP_DIR/salus-mysql-legacy-$base.sql.gz.enc"
    if [ "$DRY_RUN" = "1" ]; then
        echo "[예정] $src → $out (확인 후 평문 삭제)"
        continue
    fi
    [ -e "$out" ] && { echo "[건너뜀] 이미 있음: $out"; continue; }
    tmp="$out.partial"
    gzip -9 -c "$src" | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$BACKUP_KEY_FILE" -out "$tmp"
    want="$(shasum -a 256 "$src" | awk '{print $1}')"
    got="$(openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$BACKUP_KEY_FILE" -in "$tmp" | gunzip -c | shasum -a 256 | awk '{print $1}')"
    if [ "$want" != "$got" ]; then
        rm -f "$tmp"
        echo "[실패] 복호화 확인이 맞지 않아 평문을 남깁니다: $src" >&2
        continue
    fi
    mv "$tmp" "$out"
    touch -r "$src" "$out"
    shasum -a 256 "$out" | awk '{print $1}' > "$out.sha256"
    touch -r "$src" "$out.sha256"
    rm -f "$src"
    echo "[옮김] $src → $out"
done
