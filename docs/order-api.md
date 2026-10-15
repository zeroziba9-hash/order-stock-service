# 주문 API 안내

실제 테스트 응답으로 생성하는 HTML 계약 가이드는 [API 문서 생성·열기](api-documentation.md)를 참고하세요.

먼저 [인증 API 안내](auth.md)의 1~3번으로 로그인하고 같은 터미널의 `$session`을 사용하세요.
주문 생성·취소에는 로그인 후 발급받은 CSRF 토큰이 필요합니다.

## 1. 상품과 재고 확인

서버가 실행된 상태에서 PowerShell 터미널에 입력합니다.

```powershell
Invoke-RestMethod 'http://localhost:8080/products' | Format-Table
```

상품 ID, 가격, 재고를 확인합니다. 아래 예시는 상품 ID가 1이고 가격이 15,000원일 때입니다.

## 2. 주문 생성

```powershell
$headers = @{ 'Idempotency-Key' = [Guid]::NewGuid().ToString() }
$csrf = Invoke-RestMethod 'http://localhost:8080/auth/csrf' -WebSession $session
$headers[$csrf.headerName] = $csrf.token
$order = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/orders' `
  -WebSession $session -Headers $headers -ContentType 'application/json' -Body '{"productId":1,"quantity":2}'
$order | ConvertTo-Json -Depth 5
```

성공 응답은 `201 Created`이고 `Location` 헤더에 `/orders/{orderId}`가 들어갑니다.
같은 키와 내용의 재요청은 `200 OK`, `Idempotency-Replayed: true`와 함께 기존 주문을 반환합니다.
키를 생략한 요청은 매번 새 주문을 생성합니다. 자세한 조건은 [중복 요청 방지](idempotency.md)를 참고하세요.

```json
{
  "orderId": 1,
  "status": "CREATED",
  "totalAmount": 30000,
  "createdAt": "2026-10-12T03:00:00Z",
  "cancelledAt": null,
  "paidAt": null,
  "refundedAt": null,
  "expiresAt": "2026-10-12T03:30:00Z",
  "expiredAt": null,
  "items": [
    {
      "productId": 1,
      "productName": "한정판 머그컵",
      "unitPrice": 15000,
      "quantity": 2
    }
  ]
}
```

ID와 시각은 예시입니다. 가격은 서버가 DB에서 읽으며 요청으로 지정할 수 없습니다.
주문 당시 상품명과 가격을 주문 항목에 저장하므로, 이후 상품 가격이 바뀌어도 기존 주문의 금액은 유지됩니다.
생성 시 주문 상태는 `CREATED`입니다. [모의 결제](payments.md)에 성공하면 `PAID`와 `paidAt`이 기록됩니다.

## 3. 주문 조회와 재고 확인

```powershell
Invoke-RestMethod "http://localhost:8080/orders/$($order.orderId)" -WebSession $session | ConvertTo-Json -Depth 5
Invoke-RestMethod 'http://localhost:8080/products' | Format-Table
```

주문 전 재고가 10개였다면 2개 주문 후 8개가 됩니다.
서버를 재시작해도 주문과 재고가 유지됩니다.

## 4. 주문 취소와 재고 복구

2번에서 생성한 `$order` 변수가 있는 같은 터미널에서 실행합니다.

```powershell
$cancelled = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/orders/$($order.orderId)/cancel" -WebSession $session -Headers $headers
$cancelled | ConvertTo-Json -Depth 5
Invoke-RestMethod 'http://localhost:8080/products' | Format-Table
```

`200 OK`와 함께 `status`가 `CANCELLED`로 바뀌고 `cancelledAt`에 UTC 취소 시각이 들어갑니다.
주문 수량이 2개였다면 재고에 2개를 돌려줍니다. 주문 금액과 항목은 이력으로 보관합니다.
이 예제는 미결제 주문의 취소입니다. 결제 완료 주문에 `/cancel`을 요청하면 `409 REFUND_REQUIRED`입니다.
결제 완료 주문의 취소는 [환불 API](refunds.md)로 전액 환불과 재고 복구를 함께 처리하세요.
환불 완료 주문에 `/cancel`을 반복 요청하면 현재 `REFUNDED` 상태를 반환하고 재고를 추가 복구하지 않습니다.

같은 취소 요청을 다시 보내도 응답은 `200 OK`이며 최초 취소 결과를 반환합니다.
재고는 추가로 늘어나지 않고 `cancelledAt`도 바뀌지 않습니다.
취소 중 DB 저장에 실패하면 취소 상태와 재고 복구가 모두 롤백됩니다.

## 오류 응답

| 조건 | HTTP | code |
|---|---|---|
| 로그인 필요 | 401 | `AUTH_REQUIRED` |
| CSRF 토큰 누락·불일치 | 403 | `ACCESS_DENIED` |
| 필수 값 누락, 0·음수·소수 수량, 잘못된 JSON | 400 | `INVALID_REQUEST` |
| 금액 계산 범위 초과 | 400 | `INVALID_ORDER_AMOUNT` |
| 요청 키 형식 오류 | 400 | `INVALID_IDEMPOTENCY_KEY` |
| 없는 상품 | 404 | `PRODUCT_NOT_FOUND` |
| 없는 주문 또는 다른 회원의 주문 | 404 | `ORDER_NOT_FOUND` |
| 재고 부족 | 409 | `INSUFFICIENT_STOCK` |
| 결제 완료 주문의 취소 | 409 | `REFUND_REQUIRED` |
| 같은 키로 다른 상품·수량 요청 | 409 | `IDEMPOTENCY_KEY_REUSED` |
| 잠금 충돌·잠금 대기 시간 초과 | 409 | `ORDER_CONFLICT` |
| 취소로 복구할 재고가 정수 저장 범위를 초과 | 409 | `STOCK_CAPACITY_EXCEEDED` |

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "상품의 재고가 부족합니다."
}
```

실패한 주문은 재고와 주문 데이터를 변경하지 않습니다.

## 트랜잭션과 잠금

1. `OrderService.create()`의 `@Transactional`이 트랜잭션을 시작합니다.
2. `findByIdForUpdate()`가 상품 행에 `PESSIMISTIC_WRITE` 잠금을 잡습니다.
3. 현재 가격과 재고를 확인하고, 재고 차감과 주문·주문 항목 저장을 실행합니다.
4. 커밋하면 모든 변경이 함께 반영됩니다. 예외가 발생하면 모두 롤백됩니다.
5. 커밋 또는 롤백 시 잠금이 해제되어 다음 주문이 최신 재고를 확인합니다.

같은 상품의 주문을 직렬화하는 방식입니다. 현재 API는 상품 한 종류만 처리합니다.
여러 상품을 한 번에 주문하는 기능을 추가할 때는 상품 ID 순서로 잠금을 획득하는 등 교착 상태를 고려해야 합니다.

관련 공식 문서: [Spring 트랜잭션](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html),
[Spring Data JPA 잠금](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html).

취소는 주문 행을 먼저 잠그고 상태를 확인합니다. 취소된 상태면 그대로 반환하고,
최초 취소일 때만 상품 행도 잠가 재고를 복구합니다. 서로 다른 주문의 취소와 신규 주문도
같은 상품 행 잠금을 공유하므로 재고 변경을 서로 덮어쓰지 않습니다.
`V4` 마이그레이션은 기존 주문을 보존하면서 취소 시각과 상태 제약 조건을 추가합니다.

## 검증 범위와 다음 단계

통합 테스트는 개발 DB와 계정을 분리한 `order_stock_test`에서 실행됩니다.
정상 주문, 입력 오류, 재고 부족, 주문 당시 가격 보존, 저장 실패 후 롤백을 검증합니다.
20개의 동시 요청에 재고 10개가 정확히 10개만 판매되는지도 확인합니다.
같은 주문에 취소 요청 20건이 들어와도 재고가 한 번만 복구되는지,
여러 주문의 동시 취소와 신규 주문·취소가 섞인 경우에도 재고가 맞는지 검증합니다.
실제 SQL 실행 후 취소 저장 오류를 주입하여 상태와 재고가 함께 롤백되는지도 확인합니다.

동일한 `Idempotency-Key`를 사용한 생성 요청은 주문을 한 개만 생성합니다.
회원 인증과 본인 주문 확인을 적용했습니다. 결제 API와 상태 전이는 [모의 결제](payments.md)를 참고하세요.
