# 운영 DB 백업과 새 DB 복원

[production-db.sh](../scripts/production-db.sh)는 Linux의 Bash·Docker Compose·GNU coreutils·gzip으로 실행합니다.
`deploy/compose.prod.yaml`의 `order_stock_prod` DB와 전용 `order_prod` 계정을 확인하고 작업합니다.
앱 DB 계정 비밀번호는 DB 컨테이너 안에서 읽으며 호스트 명령 인수나 로그에 출력하지 않습니다.
Windows에서는 Docker가 연결된 WSL 등 Linux 환경에서 실행하세요.

## 백업 생성

운영 환경 파일을 준비하고 DB가 실행 중인 상태에서 저장소 루트에서 실행합니다.

```bash
bash scripts/production-db.sh backup deploy/compose.prod.env /secure-backups/order-stock
```

마지막 인수로 Compose 프로젝트 이름을 지정할 수 있습니다. 생략하면 `order-stock-prod`입니다.
출력되는 경로가 이번 백업 폴더입니다. 시각과 임의 문자열이 포함되어 기존 백업을 덮어쓰지 않습니다.
백업 경로는 **저장소 밖**이어야 하며, 도구가 저장소 내부 경로를 거부합니다.

```text
order-stock-20261013T010000Z-XXXXXXXX/
  database.sql.gz
  metadata.txt
  SHA256SUMS
```

- `database.sql.gz`: 테이블·데이터·Flyway 이력과 상품 이미지 JPEG가 포함된 압축 SQL. `--hex-blob`으로 바이너리와 세션 속성을 안전하게 덤프합니다.
- `metadata.txt`: 형식·DB 이름·출처 프로젝트·생성 시각·테이블 수.
- `SHA256SUMS`: 압축 SQL과 메타데이터의 SHA-256 체크섬.

새 백업 폴더는 권한 700, 파일은 600으로 생성합니다.
생성 중에는 `INCOMPLETE` 표시가 있으며, 덤프·압축·체크섬 생성이 끝나면 제거합니다.
실패한 명령은 성공 상태를 반환하지 않으며, `INCOMPLETE`가 있는 백업은 복원하지 않습니다.

InnoDB의 일관된 스냅샷을 위해 `--single-transaction --quick`을 사용합니다.
백업 중 일반 주문 요청은 받을 수 있지만, 배포·Flyway 마이그레이션·테이블 구조 변경과 동시에 실행하지 않습니다.
`--no-tablespaces --set-gtid-purged=OFF`로 스키마 계정에 필요 없는 전역 권한을 요구하지 않습니다.
`--skip-add-drop-table --skip-add-locks`로 기존 테이블 삭제문과 복원 중 명시적인 테이블 잠금을 생략합니다.
설정의 근거는 [MySQL 8.4 mysqldump 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)를 참고하세요.

백업에는 이메일·비밀번호 해시·주문 정보와 세션 ID·인증 주체·CSRF 토큰도 들어갈 수 있으므로 접근을 제한하고 서버 밖에도 암호화하여 보관합니다.
체크섬은 파일 손상을 확인하는 용도이며, 본인이 만든 신뢰할 수 있는 백업만 복원합니다.
DB 백업과 함께 배포 커밋·운영 환경 파일·Caddy 인증서 볼륨의 보관도 별도로 관리하세요.
백업 실행 시점 이후의 변경은 이 백업에 포함되지 않습니다. 현재 도구는 수동 백업이며 예약 실행을 만들지 않습니다.

## 다른 빈 DB에 복원

원본 DB 볼륨을 보존하고 **새 서버 또는 새 Compose 프로젝트**에 복원합니다.
같은 호스트에서 실행할 경우 아래처럼 새 프로젝트 이름을 사용합니다.
실제 장애 복구에서는 백업 생성 시점의 앱 커밋과 마이그레이션 파일을 준비합니다.

1. 별도의 환경 파일 `deploy/recovery.env`를 만들고 `APP_DOMAIN`과 두 DB 비밀번호를 채웁니다.
   운영 환경 파일과 동일한 키를 사용하고 권한 600으로 제한합니다. Git에서 제외되는 파일입니다.
2. 앱·프록시를 시작하기 전에 새 프로젝트의 DB만 시작합니다.

```bash
docker compose --project-name order-stock-recovery --env-file deploy/recovery.env -f deploy/compose.prod.yaml up --wait --wait-timeout 240 db
```

3. 출력된 백업 경로를 지정해서 복원합니다. `ARCHIVE_DIRECTORY`를 실제 경로로 바꿉니다.

```bash
bash scripts/production-db.sh restore deploy/recovery.env ARCHIVE_DIRECTORY order-stock-recovery
```

복원에는 대상 프로젝트 이름을 반드시 명시해야 합니다. 다음 경우 SQL을 실행하지 않고 거부합니다.

- 백업 누락·미완료 표시·체크섬 불일치·압축 손상·잘못된 메타데이터.
- 대상 프로젝트의 앱이나 프록시가 실행 중임.
- 대상 DB 이름·접속 계정이 예상과 다름.
- 대상 DB에 테이블 또는 뷰가 이미 있음.

도구가 기존 테이블이나 DB를 삭제하지 않습니다. 다른 관리 작업과 동시에 복원하지 마세요.
V13 이상 백업을 가져온 뒤에는 대상 DB의 `app_sessions` 행과 연결된 속성을 모두 삭제합니다.
백업 이후 로그아웃한 쿠키가 다시 인증되는 것을 막기 위한 절차이며 회원·주문과 세션 표 구조는 유지합니다.
구버전 백업에 세션 표가 없으면 이 정리를 건너뜁니다. [세션 복구 정책](session-persistence.md)을 참고하세요.
SQL 가져오기는 DDL을 포함하므로 전체를 하나의 트랜잭션으로 롤백할 수 없습니다.
중간에 오류가 나면 대상에 일부 테이블이 남을 수 있으며, 해당 DB에 재시도하지 말고 원인을 확인한 뒤
새로운 빈 복구 환경을 준비합니다. 원본 볼륨을 지워서 빈 DB로 만드는 절차는 사용하지 않습니다.

4. 같은 앱 버전의 이미지를 준비하고 먼저 앱만 실행하여 Flyway와 준비 상태를 확인합니다.

```bash
docker compose --project-name order-stock-recovery --env-file deploy/recovery.env -f deploy/compose.prod.yaml build --pull
docker compose --project-name order-stock-recovery --env-file deploy/recovery.env -f deploy/compose.prod.yaml up --no-build --wait --wait-timeout 240 app
docker compose --project-name order-stock-recovery --env-file deploy/recovery.env -f deploy/compose.prod.yaml exec -T app curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

5. [HTTPS 배포 안내](https-deployment.md)를 따라 복구 환경으로 트래픽을 연결합니다.
   같은 호스트에서는 기존 프록시가 사용 중인 80·443 포트를 먼저 조정해야 하며, 기존 DB 볼륨은 보존합니다.
   새 서버라면 DNS·방화벽·공개 인증서도 확인합니다. 복원 도구가 과거 세션을 무효화하므로 다시 로그인합니다.
6. 상품·재고, 기존 계정의 로그인, 본인 주문·결제·환불 이력, 같은 주문 요청 키의 재요청을 확인합니다.

## 실제 복구 자동 검증

[Docker Compose CI](../.github/workflows/docker.yml)의 `backup-restore` 작업은 다음 순서로 실행합니다.

1. 일회용 원본 DB에서 실제 HTTP 회원가입·주문·취소·모의 결제·환불 데이터를 생성합니다.
2. 운영용 백업 도구로 압축 백업을 만들고 파일 권한을 검사합니다.
3. 다른 MySQL 컨테이너·빈 볼륨으로 복원합니다. 기존 볼륨의 재시작과 별도로 검증합니다.
4. 복원된 세션·속성이 비어 있는지 확인하고 `prod`·HTTPS에서 과거 쿠키가 거부되는지 검사한 뒤 기존 계정으로 새로 로그인합니다.
5. 상품·주문·재고·요청 키·결제·환불 이력·계정 장바구니·확인 주문 재전송 결과가 원본 스냅샷과 같은지 실제 API로 비교합니다. 실제 관리자 업로드 이미지의 바이트 해시·이미지 변경 이력과 완료/미완료 서버 복구 요청도 비교합니다.
6. 실행 중인 앱, 손상·미완료 백업, 비어 있지 않은 대상 DB의 복원 거부를 검사합니다.

CI에서 만든 백업·회원 비밀번호는 아티팩트로 업로드하지 않습니다. 종료 시 일회용 DB 볼륨을 정리합니다.
실제 서버의 백업 저장소·암호화·보관 기간·복구 시간과 공개 DNS 전환은 배포 환경이 정해지면 검증합니다.
