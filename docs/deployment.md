# 배포 환경과 CI

배포 파일과 로컬 검증을 준비하고 [GitHub 저장소](https://github.com/zeroziba9-hash/order-stock-service)에 연결합니다.
실제 배포 서버와 도메인은 아직 연결하지 않았습니다.
이 문서는 Java 17 실행 JAR와 Linux systemd를 기준으로 합니다.
본인 컴퓨터에서 앱과 DB를 함께 실행하는 방법은 [Docker Compose 안내](docker.md)를 참고하세요.
Compose는 로컬 학습용이며 이 문서의 운영 `prod` 구성과 구분합니다.

## 환경별 동작

| 환경 | 활성화 | DB 접속 정보 | 모의 결제·환불 쓰기 | 세션 쿠키 |
|---|---|---|---|---|
| 로컬 | 기본 프로필 `local` | `.local/mysql/spring-local.properties` 또는 환경 변수 | 허용 | HttpOnly, SameSite=Lax |
| 테스트 | 테스트의 `test` 프로필 | 테스트 전용 파일 또는 환경 변수 | 허용, 차단 테스트는 별도 설정 | 로컬 HTTP 사용 |
| 운영 | `SPRING_PROFILES_ACTIVE=prod` | 배포 환경 변수 | 차단 | Secure, HttpOnly, SameSite=Lax |

공통 기본값은 모의 쓰기 차단입니다. `local`·`test`에서 명시적으로 허용합니다.
운영에서는 결제·환불 POST가 `404 MOCK_API_DISABLED`이고 기존 이력 GET은 본인만 조회할 수 있습니다.
실제 결제사는 아직 연결하지 않았으므로 운영 환경에서는 주문 생성·조회·미결제 취소와 기존 이력 조회를 제공합니다.

운영 시작 시 다음 설정을 DB 연결·Flyway 실행 전에 검사합니다.

- DB URL·사용자·비밀번호가 모두 있어야 합니다. 운영은 `.local/` 접속 파일을 자동으로 읽지 않습니다.
- MySQL 전용 계정을 사용해야 합니다. `root`는 거부합니다.
- 모의 쓰기 허용 또는 Secure 쿠키 해제 설정을 거부합니다.
- `prod`와 `local`·`test`의 동시 활성화를 거부합니다.

환경 변수 이름은 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`입니다.
프로필을 생략하면 로컬 실습용으로 시작하므로 실제 서버에는 반드시 `prod`를 지정하세요.
프로필 규칙은 [Spring Boot 공식 문서](https://docs.spring.io/spring-boot/4.0/reference/features/profiles.html)를 참고하세요.

## CI: push·PR마다 실제 MySQL 테스트

[워크플로](../.github/workflows/ci.yml)는 `push`, `pull_request`, 수동 실행에 반응합니다.
Ubuntu에서 Java 17과 일회용 MySQL 8.4 컨테이너를 준비한 후 다음을 실행합니다.

```bash
bash ./gradlew --no-daemon test bootJar
```

테스트 DB는 `order_stock_test`, 계정은 해당 스키마에만 접근하는 `order_test`입니다.
워크플로 안의 비밀번호는 이 일회용 컨테이너 전용 값이며 실제 DB 자격 증명이 아닙니다.
실제 운영 비밀 값이나 `.local/`을 CI에 복사하지 않습니다. 테스트 프로필은 접속 파일 없이도 환경 변수로 실행됩니다.
각 통합 테스트는 DB 이름을 확인하고 테스트 데이터만 정리합니다.

실패해도 HTML·JUnit XML 보고서를 `test-reports` 아티팩트에 올립니다.
성공한 경우 실행 JAR를 `order-stock-service` 아티팩트에 올리며 보관 기간은 7일입니다.
워크플로는 저장소 읽기 권한만 사용하며 자동 배포는 하지 않습니다.
외부 액션은 확인된 커밋 SHA로 고정했고, Gradle 배포 ZIP의 SHA-256도 고정했습니다.

GitHub에 프로젝트를 올린 다음 **Actions → Backend CI**에서 결과를 확인합니다.
실행 결과는 [Backend CI](https://github.com/zeroziba9-hash/order-stock-service/actions/workflows/ci.yml)에서 확인합니다.
설정은 [GitHub의 서비스 컨테이너](https://docs.github.com/en/actions/tutorials/use-containerized-services)와
[Gradle 액션](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)의 공식 안내를 기준으로 합니다.

## 실행 JAR 만들기

로컬에서는 MySQL을 시작한 뒤 프로젝트 터미널에서 실행합니다.

```powershell
.\scripts\start-db.ps1
.\gradlew.bat test bootJar
```

결과는 `build/libs/order-stock-service-0.0.1-SNAPSHOT.jar`입니다.
JAR에 DB 접속 파일·비밀번호·DB 데이터가 포함되지 않습니다.
운영 DB는 별도 `order_stock_prod` 스키마와 계정을 준비하고, 현재는 Flyway 마이그레이션에 필요한 DDL 권한도
해당 스키마에만 부여합니다. 운영 데이터와 테스트 데이터를 같은 DB로 지정하지 마세요.
마이그레이션 전 백업을 준비하고 적용된 `V1`~`V8` 파일은 변경하지 않습니다.

## Linux 서버 실행 예시

Java 17, MySQL 접속, HTTPS를 처리할 역방향 프록시가 준비된 서버에서 실행하는 예시입니다.
실제 서버에서 아래 명령을 아직 실행하지 않았습니다.

1. 로그인 셸이 없는 전용 OS 계정 `orderstock`과 `/opt/order-stock`, `/etc/order-stock`을 준비합니다.
2. 실행 JAR를 `/opt/order-stock/app.jar`로 복사합니다. 앱 계정에는 읽기 권한을 부여하고 쓰기는 배포 계정이 관리합니다.
3. [환경 파일 예제](../deploy/order-stock.env.example)를 `/etc/order-stock/order-stock.env`로 복사하고
   실제 DB 정보를 Git 밖에서 채웁니다. 이 파일은 root 소유·권한 `600`으로 보관합니다.
4. [systemd 서비스 예제](../deploy/order-stock.service)를 `/etc/systemd/system/order-stock.service`로 복사합니다.
   서버의 Java 경로가 다르면 `ExecStart`를 조정합니다.
5. 다음 명령으로 시작하고 상태와 로그를 확인합니다.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now order-stock
sudo systemctl status order-stock
sudo journalctl -u order-stock -f
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

`EnvironmentFile`은 systemd가 읽는 설정 파일입니다. 셸에서 `source`하거나 Git에 실제 비밀 값을 저장하지 마세요.
예제의 `sslMode=VERIFY_IDENTITY`는 DB 서버 인증서를 검증하는 설정이며 DB 인증서를 JVM이 신뢰하도록 준비해야 합니다.
현재 컴퓨터의 로컬 MySQL 접속 설정은 운영 TLS 설정으로 사용하지 않습니다.

앱은 기본적으로 `127.0.0.1:8080`에 바인딩합니다. 외부 요청은 프록시의 HTTPS 주소로 받아 내부 앱에 전달합니다.
운영 쿠키가 Secure이므로 브라우저 실습도 HTTPS 주소를 사용해야 합니다.
서버가 `Forwarded` 헤더를 신뢰하도록 별도 설정하지 않았으며, 앱 포트를 외부에 직접 노출하지 않습니다.
컨테이너 등 별도 네트워크에서는 실제 배치에 맞게 `SERVER_ADDRESS`를 지정하세요.

## 상태 확인·종료·업데이트

- `/actuator/health/liveness`: 프로세스 생존 상태.
- `/actuator/health/readiness`: 준비 상태와 DB 연결. DB가 응답하지 않으면 준비 상태가 실패합니다.
- 상태 상세·DB 접속 정보는 공개 응답에 포함하지 않습니다.
- 종료 신호를 받으면 최대 20초 동안 진행 중 요청을 마무리합니다. systemd는 최대 30초 기다립니다.
- 로그는 표준 출력으로 내보내므로 systemd에서는 journal로 확인합니다.

업데이트는 서비스를 종료한 뒤 검증한 JAR를 교체하고 다시 시작합니다.

```bash
sudo systemctl stop order-stock
# 검증한 새 JAR를 /opt/order-stock/app.jar로 교체
sudo systemctl start order-stock
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

세션은 서버 메모리에 있으므로 재시작하면 다시 로그인해야 합니다. 현재 단일 인스턴스를 기준으로 합니다.
이전 JAR로 되돌릴 때도 DB 마이그레이션 호환성을 확인해야 합니다. JAR 교체만으로 DB가 되돌아가지는 않습니다.

## 현재 검증 범위

운영 보호 규칙과 모의 API 차단을 포함한 테스트 151개와 actionlint 워크플로 검사를 통과했습니다.
`.local/` 없는 복사본에서도 환경 변수만으로 같은 테스트와 실행 JAR 빌드를 확인했습니다.
별도 포트·테스트 DB로 실제 운영 프로필을 실행하여 비밀 설정 누락 시 시작 거부,
Secure 쿠키, 상태 확인 API, 로그인 후 모의 결제·환불 차단과 이력 조회를 검증했습니다.
기본 `local`의 실제 결제·환불 흐름과 재시작 후 데이터 보존도 확인했습니다.
GitHub 호스팅 러너의 결과는 위 CI 링크에서 확인합니다. Linux systemd·HTTPS 프록시 배포는 아직 수행하지 않았습니다.
Docker Desktop의 WSL 2 Linux 엔진에서도 소스 이미지 빌드, 실제 HTTP 인증·주문·결제·환불,
컨테이너 재생성 후 DB 데이터 보존을 검증했습니다. 구성과 별도 CI는 [Docker 안내](docker.md)에 정리합니다.
다음 단계는 배포 서버 선택과 HTTPS·운영 DB 연결입니다.
