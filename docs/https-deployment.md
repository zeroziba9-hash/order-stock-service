# 단일 Linux 서버에 HTTPS로 배포하기

`deploy/compose.prod.yaml`은 MySQL·Spring Boot·Caddy를 한 서버에서 실행하는 운영용 구성입니다.
로컬 개발용 `compose.yaml`과 별개의 Compose 프로젝트·DB·볼륨을 사용합니다.
앱은 `prod`로 실행하며 모의 결제·환불 쓰기는 차단합니다.
현재 외부 서버·DNS·공개 인증서 발급은 연결하지 않았습니다. 이 문서는 서버가 준비된 후 적용할 절차입니다.

## 구성

```text
브라우저 → HTTPS 443 → Caddy → app:8080 → db:3306
             HTTP 80 → HTTPS로 리다이렉트
```

- 외부 공개 포트는 Caddy의 80·443 TCP뿐입니다. 앱 8080·MySQL 3306·Caddy 관리 2019는 공개하지 않습니다.
- DB는 `internal`인 backend 네트워크에 있으며 앱만 같은 네트워크에 연결합니다.
- Caddy와 앱은 별도 frontend 네트워크로 연결합니다.
- Caddy는 인증 IP 헤더를 덮어씁니다. 개별 고객 IP로 제한하려면 실제 프록시 주소를 `AUTH_TRUSTED_PROXIES`에 등록하세요.
  비어 있으면 고객들이 프록시 IP의 한도를 공유합니다. [신뢰 주소 설정](auth-limits.md#프록시와-ip-신뢰)을 참고하세요.
- DB는 `order_stock_prod` 스키마와 전용 `order_prod` 계정을 사용합니다.
- 브라우저와 프록시 사이에는 HTTPS를 사용합니다. 프록시→앱 HTTP 및 앱→DB 연결은 같은 호스트의
  Docker 네트워크 안에서만 전달합니다. 다른 호스트나 외부 DB로 변경하면 해당 구간의 TLS도 별도로 구성해야 합니다.
- `mysql-data`에 DB, `caddy-data`에 인증서·개인키를 보관합니다. 컨테이너를 재생성해도 유지됩니다.

## 1. 서버·도메인 준비

Docker Engine과 Compose 플러그인이 설치된 Linux 서버가 필요합니다.
로컬 HTTPS 테스트용 override까지 사용하려면 Compose 2.24.4 이상이어야 합니다.
Docker 설치는 [공식 Engine 설치 안내](https://docs.docker.com/engine/install/)를 참고하세요.

도메인의 DNS A 레코드를 서버의 공인 IPv4 주소에 연결합니다. AAAA 레코드가 있다면 IPv6도 올바른 서버를
가리키고 접속 가능해야 합니다. 방화벽·보안 그룹에서 외부의 TCP 80·443 접속을 허용합니다.
앱과 DB 포트를 추가로 열 필요는 없습니다.

Caddy는 도메인이 지정되고 서버에 접속할 수 있으면 공개 인증서 발급·갱신과 HTTP→HTTPS 전환을 처리합니다.
동작 조건은 [Caddy HTTPS 시작 안내](https://caddyserver.com/docs/quick-starts/https)와
[자동 HTTPS 문서](https://caddyserver.com/docs/automatic-https)를 참고하세요.

## 2. 운영 환경 파일 만들기

서버에 저장소를 받아 검증한 커밋으로 체크아웃한 뒤, 저장소 루트에서 실행합니다.

```bash
umask 077
cp deploy/compose.prod.env.example deploy/compose.prod.env
chmod 600 deploy/compose.prod.env
```

이미 운영 환경 파일이 있다면 덮어쓰지 않습니다. 다음 세 값을 편집기로 채웁니다.

| 설정 | 값 |
|---|---|
| `APP_DOMAIN` | 본인 도메인, 예: `api.example.com`. `https://`·포트·경로 없이 입력 |
| `PROD_MYSQL_PASSWORD` | 앱 DB 계정용 임의의 비밀번호 |
| `PROD_MYSQL_ROOT_PASSWORD` | 앱 계정과 다른 임의의 관리자 비밀번호 |

비밀번호는 `openssl rand -hex 24`를 각각 한 번씩 실행해서 만들 수 있습니다.
실제 값은 Git에 올리거나 셸에서 `source`하지 않습니다. Compose가 파일을 읽어서 필요한 컨테이너에만 전달합니다.
로컬·테스트·운영의 비밀번호를 재사용하지 않습니다. 비어 있으면 Compose 시작이 실패합니다.
`config` 출력에는 비밀번호가 포함될 수 있으므로 검증에는 `config --quiet`를 사용합니다.

## 3. 빌드·검증·실행

저장소 루트에서 **운영 파일만** 지정합니다. 로컬 `compose.yaml`과 함께 합치지 않습니다.

```bash
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml config --quiet
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml build --pull
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml run --rm --no-deps proxy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml up --no-build --wait --wait-timeout 240
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml ps
```

`--wait`는 DB·앱·프록시 프로세스의 준비를 확인합니다. 공개 DNS·인증서까지 검증한 결과는 아니므로
실제 도메인으로 다음 요청도 확인합니다. 아래 주소를 본인 도메인으로 바꿉니다.

```bash
curl --fail --show-error https://api.example.com/actuator/health/readiness
curl --fail --show-error https://api.example.com/products
curl --head http://api.example.com/products
```

준비 상태는 `{"status":"UP"}`, 상품은 JSON 배열이어야 합니다.
HTTP 요청은 같은 도메인의 HTTPS로 전환되어야 합니다. 인증서 검증을 끄는 `-k`를 붙이지 않습니다.
인증서 발급이 실패하면 DNS·80/443 도달 여부와 프록시 로그를 확인합니다.

### 배포 결과 자동 확인

Python 3.10 이상이 있는 서버 밖의 컴퓨터에서 실행합니다.

```bash
python3 scripts/verify-deployment.py --url https://api.example.com --output build/deployment-check.json
```

HTTP→HTTPS 전환, 인증서 신뢰·호스트 이름, HTML·CSS·JS, readiness, 상품 형식,
운영 모의 기능 비활성화, 비로그인 주문 접근 차단을 총 9개 GET 요청으로 확인합니다.
회원·주문을 만들거나 운영 DB를 초기화하지 않습니다. 예상과 다른 응답은 실패로 종료합니다.
결과에는 체크 항목·상태 코드·원본 주소만 기록하며 쿠키·응답 본문은 기록하지 않습니다.
성공 결과는 실행한 컴퓨터에서 해당 주소에 도달했다는 뜻이며 전 세계 DNS 전파·인증서 자동 갱신·로그인 성공을 보증하지 않습니다.

로컬 CI에서만 `--local-test-ca`와 `--local-test-http-url`로 테스트 인증서를 전달합니다.
이 모드는 `localhost:18443`·`localhost:18081`로 제한되고 결과에 `local-test`로 표시됩니다.
실제 배포 주소는 시스템 CA로 검증하며 인증서 검증을 끄는 옵션은 없습니다.

### 배포 기록

첫 공개 배포 때 다음 정보를 개인 접속 정보 없이 기록합니다. 아직 실제 서버가 연결되지 않았으므로 공개 배포 완료로 표시하지 않습니다.

| 항목 | 기록할 내용 |
|---|---|
| 릴리스 | 배포한 Git 커밋 SHA, 이미지 ID, 배포 시각 |
| 공개 주소 | 본인 HTTPS 도메인과 외부에서 실행한 위 검증 JSON |
| 백업 | 배포 전 백업 시각, 체크섬 검증, 서버 밖 보관 및 빈 DB 복원 결과 |
| 장애 대응 | 직전 검증 이미지와 환경 설정, 마이그레이션 호환 여부 |

앱 이미지 롤백과 DB 복원은 별개입니다. DB 변경이 이전 앱과 호환되는지 확인한 후 롤백하며
Flyway 이력을 삭제하거나 운영 볼륨을 지워 되돌리지 않습니다.

```bash
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml logs --tail 100 proxy
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml logs --tail 100 app
```

## 4. HTTPS에서 로그인·주문 확인

[인증 API 안내](auth.md)와 [주문 API 안내](order-api.md)의 기본 URL을 `https://본인도메인`으로 바꿉니다.
로그인 성공 후 CSRF 토큰을 다시 받고 주문을 생성·조회·취소합니다.
세션 쿠키는 Secure·HttpOnly·SameSite=Lax이며 HTTP로 보내지 않습니다.
모의 결제·환불 POST는 `404 MOCK_API_DISABLED`입니다. 실제 결제사는 아직 연동하지 않았습니다.

회원·주문·재고와 로그인 세션은 MySQL에 남아 유효한 쿠키로 앱 재시작 뒤에도 인증을 이어갑니다.
V13 최초 적용과 백업 복원 뒤에는 다시 로그인합니다. [세션 정책](session-persistence.md)을 참고하세요.

## 5. 데이터 보존·업데이트

운영 파일의 Compose 프로젝트 이름은 `order-stock-prod`입니다.
운영에서는 같은 프로젝트 이름과 환경 파일을 계속 사용합니다. 이름을 바꾸면 다른 볼륨으로 실행될 수 있습니다.
`down`은 컨테이너를 제거하고 named volume은 남깁니다. `down --volumes`는 DB와 인증서까지 삭제하므로
운영 절차에 사용하지 않습니다. Caddy의 인증서 데이터도 캐시가 아닌 영속 데이터입니다.
[Caddy 공식 이미지 안내](https://hub.docker.com/_/caddy)를 참고하세요.

업데이트 전에는 [운영 DB 백업·복원 안내](backup-restore.md)를 따라 백업합니다.
Linux에서 다음 도구를 실행하면 저장소 밖에 압축 SQL·메타데이터·체크섬을 함께 보관합니다.

```bash
bash scripts/production-db.sh backup deploy/compose.prod.env /secure-backups/order-stock
```

명령 실패 여부와 백업 내용을 확인하고 서버 밖에도 암호화하여 보관합니다. 새 폴더의 권한은 700, 파일은 600입니다.
복원은 별도의 빈 DB를 대상으로 하며 데이터가 있는 DB나 실행 중인 앱이 있는 프로젝트에는 쓰지 않습니다.
검증한 새 커밋으로 소스를 바꾼 뒤 다음을 실행합니다.

```bash
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml build --pull
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml up --no-build --wait --wait-timeout 240
curl --fail --show-error https://api.example.com/actuator/health/readiness
```

배포 중 짧은 중단이 생길 수 있으며 현재는 단일 앱 인스턴스입니다.
이미 적용한 Flyway 마이그레이션을 수정하지 않습니다. 이전 이미지로 바꿔도 DB는 자동으로 되돌아가지 않습니다.
볼륨이 이미 초기화되어 있다면 환경 파일만 수정해 DB 계정의 비밀번호를 바꿀 수 없습니다.
DB 계정 변경과 환경 파일 변경을 함께 관리해야 합니다.

## 자동 검증과 남은 범위

[Docker Compose CI](../.github/workflows/docker.yml)의 `https` 작업은 위 운영 Compose와 같은 Caddyfile을 사용합니다.
테스트에서만 `localhost`와 loopback 포트로 바꾸어 Caddy의 임시 로컬 CA를 사용합니다.
테스트 프로세스는 복사한 공개 CA 인증서만 신뢰하며 운영체제 인증서 저장소를 변경하지 않습니다.

- TLS 검증 활성화, HTTP→HTTPS 전환, 앱·DB 포트 비공개, `prod` 프로필과 일반 사용자 실행.
- HTTPS 회원가입·로그인·로그아웃, 세션 ID 교체, 쿠키 속성, CSRF 누락 차단.
- 실제 주문·요청 재생·반복 취소·재고 복구, 운영 모의 결제·환불 쓰기 차단.

CI는 자동 배포하거나 공개 ACME 인증서를 발급하지 않습니다.
Windows의 Docker Desktop·WSL 2에서도 동일 구성의 HTTPS 인증·주문·운영 보호 동작을 검증했습니다.
별도 CI 작업은 백업을 다른 빈 MySQL 볼륨에 복원한 후 HTTPS API로 실제 데이터를 비교합니다.
실제 외부 서버에서의 공개 인증서 발급·갱신, DNS·방화벽과 백업 보관·복구 절차는 서버가 마련된 뒤 확인해야 합니다.
