# 회원가입·로그인·본인 주문 API 안내

상품 목록과 서버 상태는 누구나 조회할 수 있습니다. 주문 생성·조회·취소는 로그인이 필요합니다.
아래 명령은 같은 PowerShell 터미널에서 순서대로 실행하세요.

로그인·회원가입은 [인증 요청 제한](auth-limits.md)을 적용합니다. `429 AUTH_RATE_LIMITED`가 오면
`Retry-After`의 초만큼 기다리고 재시도하세요. 계정별 전역 잠금은 적용하지 않습니다.

## 1. 입력과 접속 준비

```powershell
$base = 'http://localhost:8080'
$email = Read-Host '이메일'
$securePassword = Read-Host '비밀번호 (8자 이상, UTF-8 기준 72바이트 이하)' -AsSecureString
$password = [System.Net.NetworkCredential]::new('', $securePassword).Password
$csrf = Invoke-RestMethod "$base/auth/csrf" -SessionVariable session
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
```

`$session`이 세션 쿠키를 보관합니다. 변경 요청에는 같은 세션에서 받은 CSRF 토큰을 보냅니다.
이메일은 소문자로 통일해 저장하며, 비밀번호는 대소문자와 공백을 구분합니다.

## 2. 최초 한 번 회원가입

이미 가입했다면 이 단계를 건너뜁니다.

```powershell
$registration = @{ email = $email; password = $password } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/auth/register" -WebSession $session `
  -Headers $headers -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($registration))
```

성공하면 `201 Created`와 `userId`, `email`, `role: USER`를 반환합니다.
회원가입으로 관리자 권한을 지정할 수 없습니다. 관리자 지정·회수는 [재고 관리 안내](admin-inventory.md)를 참고하세요.
같은 이메일이 있으면 `409 EMAIL_ALREADY_EXISTS`입니다. 가입이 곧 로그인을 의미하지는 않습니다.
DB에는 BCrypt 해시만 저장하고, 응답에 비밀번호나 해시를 포함하지 않습니다.

## 3. 로그인과 본인 확인

로그인은 `application/x-www-form-urlencoded` 형식으로 보냅니다.

```powershell
Invoke-RestMethod -Method Post -Uri "$base/auth/login" -WebSession $session `
  -Headers $headers -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ email = $email; password = $password }
Remove-Variable password, securePassword, registration -ErrorAction SilentlyContinue

# 로그인 시 세션 ID와 CSRF 토큰이 바뀌므로 새 토큰을 받습니다.
$csrf = Invoke-RestMethod "$base/auth/csrf" -WebSession $session
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod "$base/auth/me" -WebSession $session
```

로그인 성공은 `200 OK`, 잘못된 이메일·비밀번호는 `401 INVALID_CREDENTIALS`입니다.
세션 쿠키는 HttpOnly와 SameSite=Lax를 사용합니다. 세션은 30분 동안 사용하지 않으면 만료됩니다.
세션은 MySQL에 저장되므로 유효한 쿠키로 앱 재시작 후에도 로그인을 유지합니다.
처음 V13을 적용하거나 백업을 복원한 뒤에는 다시 로그인합니다. [저장·만료·복구 정책](session-persistence.md)을 참고하세요.
회원·주문·요청 키·결제·환불 이력은 MySQL에 남습니다.

## 4. 본인 주문 생성·조회·취소

상품 목록의 ID와 재고를 확인한 뒤 실행하세요.

```powershell
Invoke-RestMethod "$base/products" | Format-Table
$headers['Idempotency-Key'] = [Guid]::NewGuid().ToString()
$order = Invoke-RestMethod -Method Post -Uri "$base/orders" -WebSession $session `
  -Headers $headers -ContentType 'application/json' -Body '{"productId":1,"quantity":2}'
$order | ConvertTo-Json -Depth 5
Invoke-RestMethod "$base/orders/$($order.orderId)" -WebSession $session | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Uri "$base/orders/$($order.orderId)/cancel" `
  -WebSession $session -Headers $headers | ConvertTo-Json -Depth 5
```

소유자는 요청 본문의 ID가 아니라 로그인한 회원 정보로 결정합니다.
다른 회원의 주문과 존재하지 않는 주문은 모두 `404 ORDER_NOT_FOUND`입니다.
요청 키는 회원별로 구분하므로 두 회원이 같은 키를 사용해도 각자의 주문을 만듭니다.
같은 회원이 같은 키·내용으로 재요청하면 본인의 기존 주문을 반환합니다.

PowerShell의 세션 쿠키는 브라우저와 별도로 관리됩니다. 본인 주문 조회도 위처럼 같은 세션으로 요청하세요.
이 예제는 미결제 주문을 취소합니다. 모의 결제는 새 주문을 만들어 [모의 결제 안내](payments.md)를 따라가세요.
결제 완료 주문은 [모의 환불](refunds.md)로 환불과 취소를 함께 처리합니다.

## 5. 로그아웃

```powershell
Invoke-RestMethod -Method Post -Uri "$base/auth/logout" -WebSession $session -Headers $headers
```

`204 No Content`를 반환하고 세션을 무효화합니다. 이후 주문 요청에는 다시 로그인이 필요합니다.

## 오류와 기존 데이터

| 상황 | HTTP | code |
|---|---|---|
| 로그인하지 않은 보호된 API 요청 | 401 | `AUTH_REQUIRED` |
| 로그인 실패 | 401 | `INVALID_CREDENTIALS` |
| CSRF 토큰 누락·불일치 | 403 | `ACCESS_DENIED` |
| 다른 회원 또는 소유자 없는 기존 주문 | 404 | `ORDER_NOT_FOUND` |
| 중복 이메일 | 409 | `EMAIL_ALREADY_EXISTS` |
| 회원가입 값 검증 실패 | 400 | `INVALID_REQUEST` |

변경 요청에 CSRF 토큰도 없다면 인증 확인보다 먼저 `403`이 반환될 수 있습니다.
기존 익명 주문과 요청 키는 소유자 NULL로 보존합니다. 신규 회원에게 자동으로 넘기지 않으며
회원 API로 조회·취소할 수 없습니다. 기존 주문을 회원에게 연결하려면 검증된 소유권을 바탕으로 별도 이관해야 합니다.

## 구현과 검증

Spring Security의 로그인·로그아웃 필터, 세션 ID 교체, 기본 CSRF 검증을 사용합니다.
주문 조회와 잠금 쿼리에 사용자 ID를 함께 넣고, 요청 키의 유니크 범위는 `(user_id, request_key)`입니다.
실제 로그인 세션, CSRF, 비밀번호 해시, 중복 가입, 타인 주문 접근 차단, 회원별 요청 키를 테스트합니다.

기본 `local`은 로컬 HTTP 환경입니다. 운영 `prod`는 Secure 쿠키를 강제하고 HTTPS 주소로 접속해야 합니다.
환경 변수·프로필·배포 방법은 [배포 안내](deployment.md)를 참고하세요.
공식 문서: [세션 관리](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html),
[CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
