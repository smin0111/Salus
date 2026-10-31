# Salus 운영 런북 (초안, 2026-10-29)

현재 운영 형태(맥북 + Docker prod compose + Tailscale Funnel + 호스트 Ollama) 기준입니다. 상시 서버로 옮기면 주소·명령을 다시 맞춥니다.
`[작성 필요]`는 운영자가 정해야 하는 값입니다. 비밀값은 이 문서에 적지 않습니다.

## 1. 연락·책임
- 운영 책임자: SALUS 운영자(개인 운영)
- 개인정보 보호책임자: 운영자 본인, 처리방침 7절의 문의 이메일(2026-10-31 확정)
- 장애 공지 방법: [작성 필요] (예: 서비스 소개 화면 배너, 지인 단체방)

## 2. 켜기·끄기·상태 확인
아래 `C`는 운영 compose 명령을 감싼 셸 함수입니다(저장소 루트에서, zsh·bash 공통): `C() { docker compose -p salus-beta --env-file .env.beta -f docker-compose.prod.yml "$@"; }`
- 켜기: `scripts/beta/start-beta.sh` (Ollama 확인 → compose up → `/api/auth/nonce` 응답 확인 → Funnel → 잠자기 방지)
- 끄기: `scripts/beta/stop-beta.sh` (DB 볼륨은 유지, 컨테이너와 그 로그는 지워짐 → 로그가 필요하면 먼저 6절 방식으로 저장)
- 외부 접속만 막기(컨테이너·로그 유지): `C stop web admin`. 다시 열기: `C start admin web`
- 상태: `C ps`, 백엔드 `curl -fsS http://127.0.0.1:8080/actuator/health`
- 로그(컨테이너당 10MB × 5개 회전): `C logs backend --tail 200`
  - 먼저 검색: `... logs backend | grep -nE "ERROR|Exception|FAILED|Caused by"`
  - 로그에는 대화 원문·요청 제목을 남기지 않습니다(길이·해시만). 로그를 외부에 공유하기 전에도 다시 확인합니다.

## 3. 백업과 복구
- 백업: `BACKUP_KEY_FILE=~/.salus-backup.key scripts/beta/backup-mysql.sh`
  - 저장: `~/salus-backups/salus-mysql-<시각>.sql.gz.enc`(AES-256, 키 파일 필수) + `.sha256`
  - 보관: `BACKUP_RETENTION_DAYS`(기본 14일). 처리방침 4절 "백업 보관 기간"과 같은 값으로 정합니다: 14일(2026-10-31 확정)
  - 외부 보관 위치: [작성 필요]. 키 파일은 백업과 다른 곳에 둡니다.
  - 주기: [작성 필요] (예: 매일 새벽 launchd/cron)
- 복구 확인(임시 컨테이너, 운영 DB 불변): `BACKUP_KEY_FILE=... scripts/beta/restore-mysql-check.sh <파일>`
  - 체크섬, 복구, 테이블 수, Flyway 마지막 버전·실패 0건을 확인합니다. 2026-10-29 임시 DB로 리허설 통과(V26, 테이블 38개).
- 실제 복구(운영 DB 교체)는 데이터 손실이 생기므로 운영 책임자가 직접 판단합니다.
  1. 쓰기 중단(MySQL은 유지): `C stop web admin backend`
  2. 현재 DB 백업: `BACKUP_KEY_FILE=... scripts/beta/backup-mysql.sh` (MySQL 컨테이너가 살아 있어야 함)
  3. 복구할 백업을 `restore-mysql-check.sh`로 먼저 확인
  4. 복구 실행: [작성 필요 — 볼륨 교체 또는 덤프 적재 방식을 정해 리허설 후 적기]
  5. `C start backend admin web` 후 로그인·대화·내 레시피 확인
- Redis(AOF 영속)는 6시간짜리 작업 세션만 있어 백업하지 않습니다. 탈퇴자의 사본은 TTL 만료로 사라집니다.

## 4. 배포와 롤백
- 배포: 베타에서 확인한 커밋만 올립니다. `git log -1` 커밋 해시를 기록한 뒤 `start-beta.sh`(compose `--build`).
- 롤백(코드): 직전 정상 커밋으로 돌아가 다시 빌드합니다. 기록해 둔 해시를 씁니다.
- 롤백(DB): Flyway는 앞으로만 갑니다. 새 마이그레이션이 들어간 배포를 되돌리면 예전 코드가 `ddl-auto=validate`에서 실패할 수 있습니다.
  - 그래서 배포 전에 백업을 먼저 하고, 마이그레이션이 있는 배포를 되돌릴 때는 그 백업으로 복구합니다(3절).
  - 기존 `V*__*.sql`은 절대 고치지 않습니다. 고칠 일이 있으면 새 버전을 추가합니다.
- 배포 전 확인: 백엔드 전체 테스트, CI의 "백엔드 MySQL 통합 테스트"(빈 MySQL에 V1부터 적용), 웹·어드민 빌드.

## 5. 자주 나는 장애
| 증상 | 먼저 볼 것 | 조치 |
|---|---|---|
| 레시피 답이 안 옴·느림 | `curl http://localhost:11434/api/tags`, 백엔드 로그 `[Ollama]` | Ollama 재시작(앱 다시 켜기), 모델(qwen3:8b, gemma3:4b) 존재 확인 |
| 웹 출처 검색 실패 | 로그 `[SearxngSearch]`, `[DuckDuckGoSearch]` | SearXNG 컨테이너 재시작. 상위 엔진 차단이면 시간이 지나면 풀림 |
| 로그인 실패 | 로그 `OAuth`, 각 제공자 콘솔의 리다이렉트 주소 | 도메인·Funnel 주소가 바뀌었으면 제공자 콘솔과 `APP_CORS_ALLOWED_ORIGINS`, 카카오 콜백 갱신 |
| 컨테이너가 반복 재시작 | `docker inspect <컨테이너> --format '{{.State.OOMKilled}}'` | 메모리 상한(compose `mem_limit`) 확인. 백엔드는 `BACKEND_MEM_LIMIT`로 조정 |
| 디스크 부족 | `docker system df`, 백업 폴더 크기 | 오래된 이미지 정리(`docker image prune`), 백업 보관 일수 확인 |
| DB 접속 실패 | `C logs mysql` | 볼륨·디스크 확인. 복구가 필요하면 3절 |

## 6. 개인정보 유출 대응 (초안, 법률 확인 필요)
아래 기한·기준은 개인정보 보호법 기준 요약이며 최신 법령과 전문가 확인이 필요합니다.
1. 인지 즉시: 외부 접속 차단은 `C stop web admin`으로 합니다. `stop-beta.sh`(compose down)는 컨테이너와 로그를 지우므로 로그를 저장하기 전에는 쓰지 않습니다.
   - 로그·상태 저장(권한 700 폴더): `D=~/salus-incident/$(date +%Y%m%d-%H%M%S); mkdir -p -m 700 "$D"; C logs --no-color --timestamps > "$D/compose.log"; C ps -a > "$D/ps.txt"; docker inspect --format '{{.Name}} {{.State.Status}} {{.State.StartedAt}} {{.State.OOMKilled}}' $(C ps -aq) > "$D/state.txt"` (`docker inspect` 전체 출력은 환경변수의 비밀값을 담으므로 저장하지 않습니다)
   - 저장한 로그에도 개인정보가 있을 수 있어 외부로 보내지 않습니다. 이후 키·토큰을 교체하고 시각·범위를 기록합니다.
2. 범위 파악: 어떤 항목(계정·건강 프로필·검진 수치·대화)이 몇 명분인지. 건강정보는 민감정보입니다.
3. 정보주체 통지: 인지 후 72시간 이내 원칙(항목, 시점·경위, 피해 최소화 방법, 대응 조치, 문의처). [법률 확인 필요]
4. 기관 신고: 1천 명 이상, 민감정보 포함, 또는 외부 불법 접근인 경우 개인정보보호위원회·KISA 신고 대상. [법률 확인 필요]
5. 재발 방지: 원인 수정, 키 교체, 처리방침·런북 갱신, 기록 보관.
- 키 교체 대상: `JWT_SECRET`, `JWT_ADMIN_SECRET`, `ADMIN_TOTP_ENCRYPTION_KEY`, DB 비밀번호, 소셜 로그인 클라이언트 비밀값, 백업 키. 교체 절차: [작성 필요]
