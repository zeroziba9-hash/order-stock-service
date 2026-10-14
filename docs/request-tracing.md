# 요청 추적 ID와 장애 로그

응답의 `X-Request-ID`로 같은 요청의 서버 로그를 찾습니다. 로그인 거부·CSRF 차단·요청 제한처럼
컨트롤러에 도달하지 않는 응답도 추적할 수 있도록 Spring Security보다 앞선 서블릿 필터에서 발급합니다.
현재 애플리케이션의 동기 MVC 요청을 대상으로 하며 분산 추적 시스템은 아닙니다.

## 확인 방법

브라우저 개발자 도구의 Network에서 요청을 선택하고 Response Headers의 `X-Request-ID`를 확인합니다.
PowerShell에서는 다음처럼 조회할 수 있습니다. 실제 실행 중인 앱의 포트를 사용하세요.

```powershell
$response = Invoke-WebRequest 'http://localhost:18080/products'
$requestId = [string]$response.Headers['X-Request-ID']
docker compose logs --no-color --since 10m app | Select-String -SimpleMatch $requestId
```

별도 Compose 프로젝트라면 로그 명령에도 동일한 `-p`, `--env-file`, `-f` 옵션을 지정합니다.
비밀번호·세션 쿠키·CSRF 토큰 대신 발생 시각·HTTP 상태·추적 ID를 장애 기록에 남깁니다.

완료 로그의 예시 형식은 다음과 같습니다. 값은 설명용이며 실제 측정값이 아닙니다.

```text
[requestId=550e8400-e29b-41d4-a716-446655440000] ... request_completed requestId=550e8400-e29b-41d4-a716-446655440000 method=GET category=products status=200 durationMs=12
```

| 필드 | 의미 |
|---|---|
| requestId | 서버가 매 HTTP 요청 새로 생성한 UUID |
| method | 허용 목록의 HTTP 메서드, 나머지는 OTHER |
| category | auth·orders·products·admin·storefront·assets·health·other 중 하나 |
| status | 완료 시 HTTP 상태. 필터 밖으로 예외가 전파되면 진단용 500 |
| durationMs | 필터 진입부터 동기 처리 종료까지의 단조 시계 경과 시간 |

정상 health·정적 자원 요청의 완료 로그는 생략하지만 헤더는 발급하며, 해당 경로의 4xx·5xx는 기록합니다.
5xx 완료 로그는 ERROR, 나머지는 INFO입니다. 요청 ID는 MDC에 넣어 처리 중 애플리케이션 로그와 연결하고
finally에서 이전 값으로 복원하거나 제거해 같은 작업 스레드의 다음 요청으로 누출하지 않습니다.

## 예기치 않은 오류

MVC에서 처리되지 않은 RuntimeException은 `500 INTERNAL_ERROR`와 공통 안내를 반환합니다.
기존 비즈니스 오류·입력 검증·잠금 충돌 핸들러가 우선하며 프레임워크가 정한 오류 상태도 유지합니다.
`unexpected_failure`에는 예외 클래스·최대 8단계에서 찾은 원인 클래스·최대 8개 애플리케이션 스택 위치만 기록합니다.
예외 메시지·SQL·원인 메시지·Throwable 전체 출력은 포함하지 않습니다.
같은 ID의 `request_completed status=500`과 연결해 실패 지점을 찾습니다.

1. HTTP 5xx 지표와 발생 시각으로 장애 범위를 확인합니다.
2. 해당 응답의 ID로 완료 로그와 `unexpected_failure`를 찾습니다.
3. 클래스·코드 위치와 최근 배포·DB 연결 상태를 확인합니다.
4. 조치 뒤 새 요청의 정상 응답과 지표 복구를 확인합니다. 재시도 요청은 새 ID를 받습니다.

## 범위와 제한

- 클라이언트가 보낸 `X-Request-ID`는 무시합니다. 요청 URL·쿼리·본문·헤더·계정·IP·멱등 키는 새 로그에 넣지 않습니다.
- 이 정책은 새 요청 요약·예외 핸들러 로그에 해당합니다. 라이브러리·DB·프록시의 모든 로그가 자동 정제되는 것은 아닙니다.
- 추적 ID는 진단용이며 접근 권한이나 주문 멱등성을 보장하지 않습니다. 재시도 시 `Idempotency-Key`는 유지하고 추적 ID는 바뀝니다.
- ID를 Prometheus 라벨로 넣지 않습니다. 로그 보관은 기존 Docker 로그 회전 설정을 사용하며 중앙 로그 검색은 아직 연결하지 않았습니다.
- JVM 시작·백그라운드 스케줄러에는 HTTP ID가 없습니다. 비동기 Servlet·별도 Executor 작업으로 MDC를 전파하지 않습니다.
- 필터 앞의 컨테이너 요청 파싱 오류, 연결 단절, 프로세스 중단에는 완료 로그나 응답 헤더를 보장하지 못합니다.
- 동기 처리 중 밖으로 전파되는 예외의 진단 상태 500은 최종 네트워크 응답을 보장하는 값이 아닙니다.

## 검증

`RequestTracingFilterTests`는 입력값의 로그 유입 차단, 예외 시 정리, 기존 MDC 보존,
정상 probe 생략·오류 기록, 4개 재사용 스레드의 동시 40요청 격리를 검증합니다.
`RequestTracingIntegrationTests`는 실제 Tomcat·보안 필터·MySQL 환경에서
200·400·401·403·409·429·404·500·503 응답, 응답 ID와 로그의 일치, 안전한 예외 위치 출력을 확인합니다.
500용 컨트롤러는 테스트 전용 설정에만 존재하며 운영 JAR에 포함되지 않습니다.
관리 포트 테스트는 추적 ID가 scrape 지표에 들어가지 않는지 확인하고,
REST Docs 테스트는 실제 추적 필터를 거쳐 상품 응답의 헤더 설명을 생성합니다.

2026-10-14 KST 로컬 검증에서 locking·conditional 각각 전체 280개 테스트와 API 문서 생성·마스킹 검사·JAR 빌드가 통과했습니다.
빈 별도 DB로 Docker 이미지를 실행해 200·401·403·404 응답의 ID와 로그 일치, 입력값 제외,
테스트 전용 오류 경로의 부재를 확인한 뒤 해당 검증용 스택만 정리했습니다.

```powershell
.\gradlew.bat test asciidoctor bootJar
```

참고: [Spring 필터의 dispatch 범위](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html),
[Logback MDC와 정리](https://logback.qos.ch/manual/mdc.html),
[Spring Boot 로그 패턴](https://docs.spring.io/spring-boot/reference/features/logging.html).
