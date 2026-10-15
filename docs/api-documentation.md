# 테스트에서 생성하는 API 가이드

Spring REST Docs 4.0.1(Boot 4.0.8 관리 버전)과 AsciidoctorJ로 인증·상품·주문·모의 결제·환불·관리자 상품/재고/전체 원장의 HTML 가이드를 생성합니다.
요청·응답 JSON, 필드 설명, 경로·쿼리·폼 파라미터, 멱등 응답 헤더를 테스트와 연결합니다.
기존 [인증 API 안내](auth.md)·[주문 API 안내](order-api.md)는 따라 해보는 설명이고, 생성 가이드는 API 계약을 확인하는 자료입니다.

Gradle 플러그인 저장소는 `settings.gradle`에서 Maven Central과 Plugin Portal을 명시합니다.
플러그인 전이 의존성은 Central에서 직접 조회해 Portal의 프록시 경로에만 의존하지 않습니다.
[Gradle 플러그인 저장소 설정](https://docs.gradle.org/9.7.1/userguide/plugins_intermediate.html#sec:custom_plugin_repositories)을 참고하세요.

## 문서 열기

실행 중인 앱은 필요하지 않지만 실제 MySQL 테스트 DB가 필요합니다.
[MySQL 안내](mysql.md)의 `order_stock_test`와 전용 `order_test` 계정을 준비하세요.
개발·운영 DB를 지정하지 마세요. 문서 테스트는 DB 이름을 확인한 뒤 테스트 데이터를 비웁니다.
테스트 계정은 개발 DB에 접근할 수 없는 계정을 사용합니다.

Windows에서 저장소 루트 기준:

```powershell
.\gradlew.bat --no-daemon test asciidoctor bootJar
Start-Process .\build\docs\api\index.html
```

Linux/macOS:

```bash
bash ./gradlew --no-daemon test asciidoctor bootJar
```

결과 `build/docs/api/index.html`은 CSS를 포함하는 단일 HTML 파일입니다. 인터넷 없이 브라우저에서 읽을 수 있습니다.
`bootJar`는 문서와 독립적이므로 Docker 이미지 빌드에는 테스트 DB가 필요하지 않습니다.
앱 JAR에 문서를 포함하거나 공개 URL을 추가하지 않습니다.

GitHub에서는 해당 커밋의 [Backend CI](https://github.com/zeroziba9-hash/order-stock-service/actions/workflows/ci.yml)
성공 실행 → **Artifacts → api-documentation-locking 또는 api-documentation-conditional**을 내려받아 압축을 풀고 `index.html`을 엽니다.
보관 기간은 7일입니다. 지나면 같은 커밋에서 다시 실행하거나 로컬에서 생성합니다.
`build/`와 생성 스니펫은 Git에 커밋하지 않습니다.

## 검증 범위

- 실제 MySQL·서비스·보안 필터를 사용하는 `ApiDocumentationTests` 11개에서 대표 요청을 실행합니다.
- 실제 CSRF 발급·회원가입·폼 로그인·토큰 재발급·로그아웃을 거칩니다. 인증을 가짜 principal로 대체하지 않습니다.
- 전용 인증 제한 통합 테스트는 실제 연속 실패 후 `429 AUTH_RATE_LIMITED`와 `Retry-After`를 문서로 생성합니다.
- 실제 상품 수정 후 변경 이력 조회의 전후 스냅샷·관리자 ID·시각·상품별 커서를 문서화합니다.
- 주문 키 사용/생략, `201` 신규 / `200` 재요청, 커서 20건·다음 페이지·빈 페이지를 기록합니다.
- 결제·환불 성공/실패와 각각의 재요청, 이력, `PAID`·`CANCELLED`·`REFUNDED`·`EXPIRED` 상태를 기록합니다.
- 관리자 수동 조정 신규·재응답·상품별 이력·일반 회원 차단과 인증 응답의 역할 필드를 기록합니다.
- 상품 0개 등록·판매 중지·수정 버전·전체 관리자 목록·원장 잔액 대조를 기록합니다.
- 대표 `400`·`401`·`403`·`404`·`409`와 배치 실행 전 늦은 결제 거절을 기록합니다.
- 엄격한 `requestFields`·`responseFields`를 사용하므로 미문서화 필드·필수 필드 누락·타입 불일치가 테스트를 실패시킵니다.
  nullable 시각·커서는 optional로 기술하며 `null`을 포함한 여러 상태의 응답 예제를 제공합니다.
- 테스트가 다시 실행되면 이전 스니펫을 지워 오래된 응답이 새 문서에 섞이지 않게 합니다.
- 문서에서 누락된 스니펫을 참조하거나 변환 경고가 생기면 문서 빌드를 실패시킵니다.

헬스 응답·모든 오류 코드·모든 경쟁 상황을 이 가이드 하나에서 검증하지는 않습니다.
기능별 통합 테스트와 브라우저·부하·업그레이드 CI를 함께 확인하세요.
문장으로 작성한 정책은 자동 추출되지 않으므로 API 동작을 바꿀 때 함께 검토해야 합니다.

## 인증 값 처리

HTTP 실행과 검증을 마친 뒤, **문서 생성 직전**에 비밀번호·CSRF 토큰을 치환하고 `Set-Cookie`를 제거합니다.
`verifyApiSnippets`가 테스트 비밀번호·마스킹되지 않은 토큰/쿠키를 발견하면 내용은 출력하지 않고 빌드를 중단합니다.
테스트 계정 이메일은 `example.test`를 사용합니다. 개발 DB 비밀번호나 실제 사용자 세션을 문서에 사용하지 않습니다.

MockMvc는 HTTP 서버 대신 세션 객체를 직접 연결합니다. 따라서 HTTP 스니펫에는 쿠키가 생략되며,
실제 클라이언트에서는 같은 출처 브라우저 또는 쿠키를 보관하는 클라이언트를 사용해야 합니다.
마스킹된 값을 그대로 복사해서 호출할 수는 없습니다. 실행 예제는 [인증 API 안내](auth.md)를 참고하세요.

## API 변경 시

1. 컨트롤러·DTO·정책을 변경합니다.
2. `src/test/java/com/zeroziba/orderstock/docs/ApiDocumentationTests.java`의 기대값과 필드 설명을 함께 변경합니다.
3. `src/docs/asciidoc/index.adoc`의 정책 설명·예제 배치·API 목록을 맞춥니다.
4. 위 전체 생성 명령을 실행하고 HTML을 확인합니다. 테스트 실패 시 기존 문서를 제출하지 않습니다.
5. 기능 브랜치 PR에서 코드·문서·CI의 `api-documentation-locking` 또는 `api-documentation-conditional`을 함께 검토합니다.

참고: [Spring REST Docs 시작하기](https://docs.spring.io/spring-restdocs/tutorial/getting-started/index.html),
[문서용 요청·응답 전처리](https://docs.spring.io/spring-restdocs/reference/customizing-requests-and-responses.html).
