#!/usr/bin/env bash
# 백업 파일을 임시 MySQL 컨테이너에 복구해 실제로 되살릴 수 있는지 확인합니다(운영 DB는 건드리지 않음).
# 사용법: BACKUP_KEY_FILE=~/.salus-backup.key scripts/beta/restore-mysql-check.sh <salus-mysql-*.sql.gz.enc>
# 확인 내용: 체크섬, 복구 성공, 테이블 수, Flyway 마지막 버전과 실패 기록 0건. 끝나면 임시 컨테이너를 지웁니다.
set -euo pipefail

FILE="${1:-}"
BACKUP_KEY_FILE="${BACKUP_KEY_FILE:-}"
IMAGE="mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b"
NAME="salus-restore-check-$$"

fail() { echo "[중단] $*" >&2; exit 1; }

[ -n "$FILE" ] && [ -f "$FILE" ] || fail "복구할 백업 파일을 지정하세요."
[ -n "$BACKUP_KEY_FILE" ] && [ -f "$BACKUP_KEY_FILE" ] || fail "BACKUP_KEY_FILE(암호화 키 파일)을 지정하세요."
if [ -f "$FILE.sha256" ]; then
    [ "$(shasum -a 256 "$FILE" | awk '{print $1}')" = "$(cat "$FILE.sha256")" ] || fail "체크섬이 다릅니다(파일 손상)."
    echo "[확인] 체크섬 일치"
fi

PW="$(openssl rand -hex 16)"
docker run -d --rm --name "$NAME" -e MYSQL_ROOT_PASSWORD="$PW" "$IMAGE" >/dev/null
trap 'docker stop "$NAME" >/dev/null 2>&1 || true' EXIT
# 초기화 중 임시 서버는 소켓 접속만 받으므로 TCP로 확인해야 최종 서버가 뜬 것입니다.
SQL=(mysql --protocol=TCP -h127.0.0.1 -uroot -p"$PW")
READY=0
for _ in $(seq 1 90); do
    if docker exec "$NAME" "${SQL[@]}" -e 'SELECT 1' >/dev/null 2>&1; then READY=1; break; fi
    sleep 2
done
[ "$READY" = 1 ] || fail "임시 MySQL이 3분 안에 준비되지 않았습니다."

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$BACKUP_KEY_FILE" -in "$FILE" \
    | gunzip \
    | docker exec -i "$NAME" "${SQL[@]}" 2>/dev/null
echo "[확인] 복구 완료"

DB="$(docker exec "$NAME" "${SQL[@]}" -N -e \
    "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('mysql','sys','information_schema','performance_schema') LIMIT 1" 2>/dev/null)"
[ -n "$DB" ] || fail "복구된 데이터베이스가 없습니다."
TABLES="$(docker exec "$NAME" "${SQL[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB'" 2>/dev/null)"
LAST="$(docker exec "$NAME" "${SQL[@]}" -N -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM \`$DB\`.flyway_schema_history WHERE success=1" 2>/dev/null)"
FAILED="$(docker exec "$NAME" "${SQL[@]}" -N -e "SELECT COUNT(*) FROM \`$DB\`.flyway_schema_history WHERE success=0" 2>/dev/null)"
echo "[확인] 데이터베이스=$DB 테이블=$TABLES Flyway 마지막 버전=V$LAST 실패 기록=$FAILED"
[ "$FAILED" = "0" ] || fail "Flyway 실패 기록이 있습니다."
echo "[완료] 복구 확인 통과. 임시 컨테이너를 지웁니다."
