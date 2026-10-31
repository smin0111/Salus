#!/usr/bin/env bash
# 베타(prod compose) MySQL을 덤프해 압축·암호화해 저장하고, 보관 기간이 지난 백업을 지웁니다.
# 사용법: scripts/beta/backup-mysql.sh
#   BACKUP_DIR               저장 위치(기본 ~/salus-backups). 저장소 밖에 둡니다.
#   BACKUP_RETENTION_DAYS    보관 일수(기본 14). 처리방침의 백업 보관 기간과 같게 맞춥니다.
#   BACKUP_KEY_FILE          암호화 키 파일(필수). 백업에는 건강정보가 있어 암호화 없이 저장하지 않습니다.
#                            예: openssl rand -base64 48 > ~/.salus-backup.key && chmod 600 ~/.salus-backup.key
#   MYSQL_CONTAINER          덤프할 컨테이너(기본 salus-beta-mysql-1)
# 복구: scripts/beta/restore-mysql-check.sh <백업 파일>  (임시 컨테이너에 복구해 확인)
# 비밀번호는 컨테이너 안의 환경변수로만 쓰고 화면이나 파일에 남기지 않습니다.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-$HOME/salus-backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-salus-beta-mysql-1}"
BACKUP_KEY_FILE="${BACKUP_KEY_FILE:-}"

fail() { echo "[중단] $*" >&2; exit 1; }

[ -n "$BACKUP_KEY_FILE" ] && [ -f "$BACKUP_KEY_FILE" ] || fail "BACKUP_KEY_FILE(암호화 키 파일)을 지정하세요."
# 맥(BSD stat)과 리눅스(GNU stat)의 권한 조회 옵션이 다릅니다. GNU stat -f는 파일 시스템 정보를 출력한 뒤 실패해
# 두 결과를 이어 붙이면 600과 같지 않으므로, 운영체제별로 한 명령만 씁니다.
if [ "$(uname -s)" = "Darwin" ]; then
    KEY_MODE="$(stat -f '%Lp' "$BACKUP_KEY_FILE")"
else
    KEY_MODE="$(stat -c '%a' "$BACKUP_KEY_FILE")"
fi
[ "$KEY_MODE" = "600" ] \
    || fail "키 파일 권한은 600이어야 합니다: chmod 600 $BACKUP_KEY_FILE"
[[ "$BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]] && [ "$BACKUP_RETENTION_DAYS" -ge 1 ] || fail "BACKUP_RETENTION_DAYS는 1 이상의 숫자여야 합니다."
docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || fail "$MYSQL_CONTAINER 컨테이너가 없습니다."

umask 077
mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/salus-mysql-$STAMP.sql.gz.enc"
TMP="$OUT.partial"
trap 'rm -f "$TMP"' EXIT

# --single-transaction: 잠금 없이 일관된 시점으로 덤프합니다(InnoDB). 실패하면 부분 파일을 남기지 않습니다.
docker exec "$MYSQL_CONTAINER" sh -c \
    'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF \
        --default-character-set=utf8mb4 --databases "$MYSQL_DATABASE"' 2>/dev/null \
    | gzip -9 \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$BACKUP_KEY_FILE" -out "$TMP"
# 파이프 중 하나라도 실패하면 set -o pipefail로 여기까지 오지 않습니다.
[ -s "$TMP" ] || fail "백업 파일이 비어 있습니다."
mv "$TMP" "$OUT"
shasum -a 256 "$OUT" | awk '{print $1}' > "$OUT.sha256"
echo "[완료] $OUT ($(du -h "$OUT" | cut -f1))"

# 보관 기간이 지난 이 스크립트의 백업만 지웁니다(파일 이름 형식이 맞는 것만).
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'salus-mysql-*.sql.gz.enc' -o -name 'salus-mysql-*.sql.gz.enc.sha256' \) \
    -mtime +"$BACKUP_RETENTION_DAYS" -print -delete | sed 's/^/[정리] /'
echo "외부 보관: 이 파일을 다른 장소(외장 디스크·클라우드)에도 복사해 두세요. 키 파일은 백업과 다른 곳에 보관하세요."
