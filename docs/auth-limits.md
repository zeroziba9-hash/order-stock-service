# 인증 요청 제한과 관측

비밀번호 해시 검사가 CPU를 사용하고 회원가입이 DB 쓰기를 만들기 때문에, 인증 서비스에 진입하기 전에 요청을 제한합니다.
CSRF 검증 뒤에 적용하며 주문 API의 회원별 멱등 처리와 별개입니다.

## 기본 정책

| 대상 | 기본 한도 | 키 |
|---|---|---|
| POST `/auth/login` 전체 요청 | 120회 / 1분 | 접속 IP |
| POST `/auth/register` 전체 요청 | 60회 / 10분 | 접속 IP |
| 로그인 실패 및 진행 중 검사 | 5회 / 5분 | 접속 IP + 정규화 이메일 |

고정 시간 구간이며 거절 요청이 만료 시간을 연장하지 않습니다. 정상 로그인은 해당 IP+이메일의 실패 수만 초기화합니다.
IP별 전체 요청 수는 성공 여부와 무관하게 증가합니다. 회원가입의 입력 오류·중복 가입도 전체 한도에 포함합니다.
동시 로그인은 검사 전에 슬롯을 예약하므로 동시에 보낸 요청으로 실패 한도를 우회할 수 없습니다.
계정만으로 전역 잠금을 걸지 않아 다른 IP의 사용자를 공격자가 잠그는 것을 피합니다. 분산 공격 방지는 별도의 외부 제한 계층이 필요합니다.

한도를 넘기면 `429 AUTH_RATE_LIMITED`, 정수 초 `Retry-After`, `Cache-Control: no-store`를 반환합니다.
계정 존재 여부와 무관하게 같은 본문을 사용합니다. CSRF 오류는 기존 `403`이며 한도를 소비하지 않습니다.
상품·CSRF GET은 제한하지 않습니다. 화면은 버튼에 남은 초를 표시하고, 시간이 지난 뒤 사용자가 직접 재시도합니다.
창을 닫았다 열거나 로그인/가입 화면을 전환해도 해당 페이지의 남은 대기 시간을 유지합니다. 페이지 새로고침 후에도 서버 제한은 유지됩니다.

## 메모리와 적용 범위

단일 프로세스 메모리이며 기본 최대 10,000개 버킷입니다. 여러 인스턴스에서 공통 한도를 공유하지 않고 재시작하면 초기화됩니다.
계정·IP는 프로세스마다 임의 키로 만든 HMAC 해시로 저장하며 원문 이메일·IP·비밀번호를 제한 맵이나 지표에 보관하지 않습니다.
일시적인 요청 입력은 인증 서비스에서 처리합니다. HMAC은 장기 익명 식별자나 감사 기록이 아닙니다.
인증 요청 시 최대 초당 한 번 만료 버킷을 정리합니다. 유효한 버킷을 임의 제거하지 않습니다.
용량이 차면 새 키는 `429`와 `Retry-After: 1`로 거절합니다. 이는 1초 뒤 반드시 슬롯이 생긴다는 보장은 아닙니다.
진행 중 검사의 버킷은 정리하지 않습니다. 긴 요청이 남아 있으면 구간이 지나도 예약 슬롯이 유지됩니다.

설정 접두사는 `app.auth.limits`입니다. 앱 설정 파일에서 다음 키를 변경할 수 있습니다.

```properties
app.auth.limits.enabled=true
app.auth.limits.login-attempts=120
app.auth.limits.registration-attempts=60
app.auth.limits.failed-logins=5
app.auth.limits.login-window=PT1M
app.auth.limits.registration-window=PT10M
app.auth.limits.failure-window=PT5M
app.auth.limits.max-entries=10000
app.auth.limits.trusted-proxies=
```

한도는 1~10,000, 구간은 1초~1시간, 용량은 16~100,000입니다. 범위를 벗어나면 시작을 거부합니다.
`prod`는 제한 해제와 자동 forwarded-header 처리를 DB 연결 전에 거부합니다.
기존 다량 fixture 통합 테스트는 제한을 해제하고, 전용 인증 통합 테스트는 제한을 켜서 실제 Security 필터·BCrypt·MySQL을 검증합니다.

## 프록시와 IP 신뢰

기본값은 TCP 접속자의 IP입니다. 클라이언트의 `X-Forwarded-For`, `Forwarded`, `X-Stockroom-Client-IP`를 신뢰하지 않습니다.
`server.forward-headers-strategy=none`을 유지합니다. 실제 접속자가 명시적으로 등록된 프록시 IP/CIDR일 때만
단일 숫자 IP인 `X-Stockroom-Client-IP`를 읽습니다. 호스트 이름·주소 목록·잘못된 값은 접속자 IP로 처리하며 DNS 조회하지 않습니다.

운영 Caddy는 이 헤더를 `{remote_host}`로 **덮어씁니다**. 앱 포트는 공개하지 않고 frontend 네트워크에는 신뢰하는 프록시와 앱만 연결합니다.
`deploy/compose.prod.env`의 `AUTH_TRUSTED_PROXIES`가 비어 있으면 Caddy 뒤의 모든 고객이 프록시 IP의 한도를 공유합니다.
개별 고객 IP로 제한하려면 배포 시 실제 Caddy 주소를 확인하고 정확한 IPv4 `/32` 또는 IPv6 `/128`로 설정합니다.

```bash
docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{"\n"}}{{end}}' \
  "$(docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml ps -q proxy)"
```

확인한 주소만 `AUTH_TRUSTED_PROXIES=172.20.0.3/32` 형태로 환경 파일에 넣고 앱을 재생성합니다. 예시 IP를 그대로 복사하지 않습니다.
프록시 재생성으로 주소가 바뀌면 다시 확인합니다. 모든 주소(`0.0.0.0/0`, `::/0`)나 호스트 전체 사설망을 신뢰 목록에 넣지 않습니다.
외부 LB/CDN을 추가하면 Caddy가 보는 원격 주소가 그 LB일 수 있으므로 신뢰 경로를 별도로 설계해야 합니다.

## 지표와 검증

| Prometheus 지표 | 고정 라벨 / 의미 |
|---|---|
| `stockroom_auth_requests_total` | endpoint: login/register, outcome: success/failure/rate_limited/error |
| `stockroom_auth_rejections_total` | endpoint, reason: request_rate/credential_failures/capacity |
| `stockroom_auth_limit_entries` | 현재 프로세스의 제한 버킷 수 |

CSRF를 통과한 인증 HTTP 요청만 집계합니다. `failure`는 로그인 401뿐 아니라 가입 입력 오류·중복 등 4xx도 포함합니다.
429는 `rate_limited`로 한 번만 세고 사유 카운터에 별도로 기록합니다. 5xx·처리 예외는 `error`입니다.
IP·이메일·회원 ID·비밀번호는 라벨로 사용하지 않습니다. 계정 존재 여부를 지표로 공개하지 않습니다.
성공 지표는 HTTP 응답 결과이며 DB 커밋 기반 주문 지표와 의미가 다릅니다. 프로세스 재시작 시 초기화됩니다.

Grafana의 인증 패널 3개는 결과·거절 사유·메모리 버킷을 보여줍니다. 알림 전송은 포함하지 않습니다.
Observability CI는 실제 로그인 실패 5회와 429 1회를 만든 뒤 Prometheus에서 증가량과 Retry-After를 확인합니다.
단위 테스트는 시간 구간·해제·용량·32개 동시 예약을, HTTP 통합 테스트는 동시 로그인 32개 중 설정된 슬롯 수만 검사를 통과하는지 검증합니다.
브라우저 CI는 격리된 앱에만 3초 구간을 적용해 데스크톱·모바일에서 대기 표시·수동 복구·가입 제한·이메일 변경 우회를 검증합니다.

근거: [Spring Security 필터 순서](https://docs.spring.io/spring-security/reference/servlet/architecture.html),
[Caddy 헤더 전달](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy),
[OWASP 로그인 제한](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#login-throttling).
