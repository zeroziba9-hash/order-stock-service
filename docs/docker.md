# Docker Compose로 실행하기

Java와 MySQL을 따로 설치하지 않고 저장소의 소스로 앱 이미지를 빌드하고 실행합니다.
Docker Engine과 Compose 플러그인이 필요합니다. Windows에서는
[Docker Desktop의 WSL 2 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)를 참고하세요.
Docker Desktop을 실행한 뒤 Linux 컨테이너 엔진이 준비되어 있어야 합니다.

```powershell
docker version
docker compose version
```

## 처음 실행

저장소 루트에서 환경 파일을 복사합니다. 이미 `.env`가 있으면 덮어쓰지 마세요.

```powershell
Copy-Item .env.example .env
```

`.env`의 `MYSQL_PASSWORD`와 `MYSQL_ROOT_PASSWORD`에 서로 다른 임의의 비밀번호를 넣습니다.
예를 들어 PowerShell에서 다음 명령을 **각각 한 번씩** 실행하여 생성한 값을 작은따옴표 안에 넣을 수 있습니다.
실제 값은 Git·스크린샷·CI 로그에 올리지 마세요.

```powershell
[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
```

위 명령은 PowerShell 7 기준입니다. Linux/macOS에서는 `openssl rand -hex 24`를 각각 실행할 수 있습니다.
예제 파일의 비밀번호는 비어 있으며, 빈 값을 그대로 두면 Compose가 시작 전에 오류로 종료합니다.
`.env`의 작은따옴표는 `$` 같은 문자를 변수로 치환하지 않고 그대로 전달합니다.
환경 파일 처리 규칙은 [Compose 공식 문서](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)를 참고하세요.

```powershell
docker compose config --quiet
docker compose up --build --wait --wait-timeout 240
docker compose ps
```

첫 빌드에는 이미지·Gradle·Java 의존성을 다운로드하므로 시간이 걸립니다.
`--wait-timeout`은 이미지 빌드 이후 컨테이너가 준비될 때까지의 대기 시간입니다.
`app`과 `db`가 `healthy`이면 다음 주소에서 확인합니다.

- 상품: [http://localhost:18080/products](http://localhost:18080/products)
- 준비 상태: [http://localhost:18080/actuator/health/readiness](http://localhost:18080/actuator/health/readiness)

호스트 앱 포트는 `.env`의 `APP_PORT`로 바꿀 수 있습니다. 컨테이너 내부 포트는 8080으로 유지합니다.
기존 로컬 서버의 8080과 호스트 MySQL의 3306을 그대로 둘 수 있습니다.
컨테이너 DB는 Docker 볼륨에 새로 생성하며 기존 `.local/mysql` 데이터를 읽거나 복사하지 않습니다.

## 실행 구조

| 항목 | 구성 |
|---|---|
| 이미지 빌드 | Temurin Java 17 JDK에서 Gradle Wrapper로 `bootJar` 실행 |
| 앱 실행 | Java 17 JRE, UID/GID 10001의 전용 사용자 |
| 앱 프로필 | 로컬 학습용 `local`: 세션 로그인·CSRF·모의 결제·환불 사용 가능 |
| DB | MySQL 8.4, `order_stock` 스키마에 접근하는 `order_app` 계정 |
| 시작 순서 | DB 계정의 TCP `SELECT 1` 성공 후 앱 시작, 앱 readiness로 준비 확인 |
| 네트워크 | 앱은 컨테이너에서 `0.0.0.0:8080`, 호스트에는 `127.0.0.1:18080`만 공개 |
| DB 접속 | 앱은 Compose 서비스 주소 `db:3306` 사용, DB 포트는 호스트에 공개하지 않음 |
| 데이터 | 프로젝트 전용 `mysql-data` named volume |
| 종료 | 앱의 진행 중 요청 마무리 최대 20초, Compose 종료 유예 30초 |
| 로그 | 표준 출력, 서비스별 파일 최대 10MB·3개 순환 |

DB 준비 대기는 [Compose의 `service_healthy`](https://docs.docker.com/compose/how-tos/startup-order/)를 사용합니다.
앱 이미지에는 JAR만 복사하며, `.dockerignore`는 빌드에 필요한 파일만 허용합니다.
`.env`, `.local`, Git 기록, 로컬 빌드 결과는 Docker 빌드 컨텍스트에서 제외합니다.
비밀번호는 실행 시 환경 변수로 전달하며 Dockerfile의 `ARG`·`ENV`에 저장하지 않습니다.
`docker compose config`와 `docker inspect`에는 실행 환경 변수가 보일 수 있으므로 출력 공유 시 주의하세요.
설정 검증만 필요하면 위처럼 `config --quiet`를 사용합니다.

이 Compose 파일은 본인 컴퓨터에서 HTTP로 실습하기 위한 구성입니다.
외부 서버에 그대로 공개하지 마세요. 실제 운영은 [배포 안내](deployment.md)의 `prod`·HTTPS·운영 DB 구성을 사용합니다.
운영 Docker 구성은 [HTTPS 배포 안내](https-deployment.md)의 별도 Compose 파일을 사용합니다.
Docker 이미지를 단독 실행할 때는 기본 `prod`이며, 운영 접속 정보를 주지 않으면 시작을 거부합니다.

## 인증·주문 실습

[인증](auth.md), [주문](order-api.md), [결제](payments.md), [환불](refunds.md) 문서의 URL에서
`http://localhost:8080`을 `http://localhost:18080`으로 바꿔 사용합니다.
서로 다른 실행 환경의 세션을 섞지 않도록 새 PowerShell 세션에서 로그인하세요.
데이터와 회원은 컨테이너 전용 DB에 저장합니다. 앱 재시작 시 다시 로그인해야 합니다.

## 로그·종료·재실행

```powershell
docker compose logs --follow --tail 100 app
docker compose logs --tail 100 db
docker compose down
docker compose up --wait --wait-timeout 240
```

로그 보기만 끝낼 때는 `Ctrl+C`를 누릅니다. 컨테이너는 계속 실행됩니다.
`down`은 이 Compose 프로젝트의 컨테이너와 네트워크를 지우고 DB 볼륨은 보존합니다.
다음 `up`은 기존 회원·주문·재고를 다시 읽습니다.
코드를 수정했다면 `docker compose up --build --wait --wait-timeout 240`으로 다시 빌드합니다.

DB CLI는 아래 명령으로 접속하고 프롬프트에서 `.env`의 앱 비밀번호를 입력합니다.

```powershell
docker compose exec db mysql --user=order_app --password order_stock
```

`MYSQL_PASSWORD`와 `MYSQL_ROOT_PASSWORD`는 **빈 볼륨의 최초 초기화**에 사용합니다.
기존 볼륨을 둔 채 `.env`만 바꾸면 DB 계정 비밀번호가 바뀌지 않아 healthcheck가 실패합니다.
그 경우 기존 `.env` 값을 복원하거나 DB에서 비밀번호를 변경한 뒤 환경 파일을 맞추세요.
자세한 동작은 [MySQL 공식 이미지 안내](https://hub.docker.com/_/mysql)를 참고하세요.

`docker compose down --volumes`는 이 프로젝트의 DB 데이터까지 삭제합니다.
테스트용 데이터를 완전히 버리려는 경우에만 실행하고, 실제 데이터가 있으면 먼저 백업하세요.
기존 Windows MySQL에는 이 명령이 영향을 주지 않습니다.

## 자동 검증

[Docker Compose CI](../.github/workflows/docker.yml)는 Ubuntu의 일회용 환경에서 다음을 검증합니다.

1. 비밀번호 누락 거부, Compose 설정 검증, 소스에서 Docker 이미지 빌드.
2. 실제 MySQL 초기화·Flyway 적용·앱 readiness·앱의 일반 사용자 실행·DB 포트 비공개.
3. 실제 HTTP 회원가입·로그인·CSRF, 주문과 재요청, 반복 취소, 모의 결제·환불과 재고 복구.
4. `down` 후 같은 볼륨으로 컨테이너를 새로 만들고 회원·주문·재고·결제·환불 이력 보존 확인.

CI의 비밀번호와 실습 회원은 매번 임의로 생성하며 CI 종료 시 전용 DB 볼륨을 정리합니다.
애플리케이션 통합 테스트 151개는 기존 [Backend CI](../.github/workflows/ci.yml)가 별도로 실행합니다.
Docker 빌드의 `bootJar` 단계 자체가 테스트 실행을 대신하지 않습니다.

Windows의 Docker Desktop 4.94.0·WSL 2 환경에서 이미지 빌드와 위 HTTP 흐름·데이터 보존을 확인했습니다.
GitHub 호스팅 러너의 결과는 [Docker Compose CI 실행 내역](https://github.com/zeroziba9-hash/order-stock-service/actions/workflows/docker.yml)에서 확인합니다.
