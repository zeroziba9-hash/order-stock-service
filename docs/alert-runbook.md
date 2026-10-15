# 경고 기준과 대응

Prometheus가 지표를 평가해 inactive(정상) → pending(지속 시간 확인) → firing(경고) 상태를 만듭니다.
조건이 사라지면 다음 평가에서 해제됩니다. 기본 평가 주기는 5초입니다.
현재 경고는 로컬 Prometheus·Grafana에서 확인합니다. 이메일·Slack 등 외부 전송과 Alertmanager는 연결하지 않았습니다.

| 경고 | 발생 조건 | 지속 시간 |
|---|---|---|
| StockroomAppDown | stockroom scrape 실패 또는 up 시계열 없음 | 1분 |
| StockroomServerErrors | 최근 5분의 비관리 HTTP 5xx 비율 >5% **그리고** 추정 증가량 ≥10건 | 2분 |
| StockroomAuthRateLimited | 최근 5분 인증 429 추정 증가량 ≥20건 | 1분 |
| StockroomAuthCapacity | 인증 제한 버킷 수 / 설정 용량 >90% | 5분 |
| StockroomExpirationFailures | 최근 5분 주문 만료 트랜잭션 롤백 증가량 >0 | 1분 |

`rate`·`increase`는 scrape 간격에 기반한 추정값입니다. 경계에서 소수 또는 실제 요청 수와 차이가 날 수 있습니다.
5xx 규칙은 `/actuator.*`를 제외하고 HTTP 409·429를 서버 오류로 세지 않습니다.
전체 HTTP 경로를 합산하므로 특정 API에만 문제가 있어도 정상 트래픽에 희석될 수 있습니다.
임계값은 로컬 프로젝트의 시작 기준이며 실제 서비스의 트래픽·오류 허용 수준에 맞게 조정해야 합니다.
인증 경고는 공격 판정이 아닙니다. 공유 IP, 오입력, 부하 실험, 제한 설정 문제도 원인이 될 수 있습니다.

## StockroomAppDown

1. [Prometheus Targets](http://localhost:19090/targets)에서 stockroom의 마지막 scrape 오류를 확인합니다.
2. Compose의 앱 실행 상태·최근 로그·DB readiness·관리 포트 9091 연결을 점검합니다.
3. 앱을 복구하고 `up=1` 및 경고 해제를 확인합니다. DB·데이터 볼륨을 삭제하지 않습니다.

이 경고는 metrics endpoint 접근 실패입니다. 앱 전체 중단, 관리 포트 설정·네트워크·scrape 인증 문제를 구분해서 조사합니다.
Prometheus 자체가 중단되면 이 규칙도 평가되지 않습니다. 별도 외부 감시가 필요합니다.

## StockroomServerErrors

1. Grafana의 HTTP 5xx 비율·처리량·DB 풀을 함께 확인하고 응답 경로별 지표로 범위를 좁힙니다.
2. 최근 배포·설정 변경과 앱 오류·DB 연결 문제를 조사합니다. 오류 응답의 `X-Request-ID`로 [요청 완료·예외 로그](request-tracing.md)를 연결합니다. 로그 공유 전에 인증 값과 개인 정보를 제거합니다.
3. 원인을 수정하거나 검증한 이전 앱 버전으로 복구하고 실제 HTTP 응답과 경고 해제를 확인합니다.

실패 수가 적으면 비율만 높아도 경고하지 않습니다. 카운터 초기화나 scrape 전에 유실된 요청은 정확한 장부로 복원하지 못합니다.

## StockroomAuthRateLimited

1. 인증 결과·차단 사유 패널에서 IP 전체 요청 한도, IP+이메일 실패 한도, 용량 제한 중 어떤 사유인지 확인합니다.
2. 프록시 신뢰 설정 때문에 여러 사용자가 한 IP 한도를 공유하는지 [인증 IP 설정](auth-limits.md#프록시와-ip-신뢰)을 점검합니다.
3. 무작정 제한을 끄거나 계정을 전역 잠금 처리하지 않습니다. 실제 요청 패턴·실수·부하 검증 여부를 확인합니다.

요청이 멈춰도 5분 계산 구간에서 증가량이 빠질 때까지 경고가 남을 수 있습니다. 앱 재시작만으로 즉시 해제된다고 가정하지 않습니다.
지표에는 이메일·IP가 없어 특정 계정을 식별할 수 없습니다.

## StockroomAuthCapacity

1. `stockroom_auth_limit_entries`와 `stockroom_auth_limit_capacity`를 같은 인스턴스 기준으로 확인합니다.
2. 만료 버킷은 인증 요청 시 정리됩니다. 트래픽이 멈추면 gauge가 직전 수를 유지해 경고가 남을 수 있습니다.
3. 정상 요청이 계속 차단되는지 확인한 뒤 요청 패턴·창 길이·용량을 점검합니다. 용량만 크게 늘려 메모리 사용을 무제한으로 만들지 않습니다.

용량 gauge는 설정값을 사용하므로 기본 10,000 외의 설정에도 비율이 맞습니다. 제한을 끄면 용량 0이며 이 경고에서 제외합니다.
AppDown과 달리 이 경고는 지표가 없을 때 발생하지 않습니다. 새 버전 배포 후 용량 지표 수집 여부도 확인하세요.

## StockroomExpirationFailures

1. 주문 만료 롤백 카운터·앱 오류·DB 상태와 미결제 주문의 기한을 확인합니다.
2. 잠금·트랜잭션·재고 복구 실패 원인을 조사하고 다음 스케줄에서 정상 처리되는지 확인합니다.
3. 주문 상태와 재고 원장이 일치하는지 조회합니다. SQL로 임의 보정하기 전에 트랜잭션 실패 원인을 확인합니다.

이 카운터는 개별 만료 트랜잭션 롤백입니다. 후보 조회 실패·스케줄러 중단 전체를 감지하지는 않습니다.

## 화면과 검증

[Prometheus Alerts](http://localhost:19090/alerts)에서 규칙별 상태·표현식·설명을 확인합니다.
Grafana의 FIRING·PENDING 패널은 현재 활성 경고 수입니다. 값 0만으로 Prometheus·규칙 로딩이 정상이라는 뜻은 아닙니다.
Targets와 `/api/v1/rules`의 규칙 상태도 함께 확인합니다. Grafana는 Prometheus 경고를 표시하며 Grafana 자체 Alerting에는 등록하지 않습니다.

`monitoring/tests/alerts.test.yaml`은 실제 규칙을 그대로 사용해 11개 시나리오·26개 기대 상태를 검증합니다.
정상·409·낮은 오류 건수·누락된 up·지속 시간·커스텀 용량·제한 해제·카운터 초기화·복구를 포함합니다.

`scripts/verify-alerts.py`는 새 `order-stock-alerts-*` 스택에서만 실행합니다. 실제 컨테이너 소유 라벨,
loopback 포트 18089/19091, 전용 DB 이름, 회원·주문 0건을 확인한 뒤 앱을 중단합니다.
실패해도 finally에서 앱을 다시 시작합니다. 기존 데모에서는 실행하지 않습니다.

```powershell
# 새 테스트 전용 환경 파일에 서로 다른 임의 비밀번호와 아래 포트를 준비합니다.
# APP_PORT=18089, PROMETHEUS_PORT=19091, GRAFANA_PORT=13001
docker compose -p order-stock-alerts-dev --env-file .local/alerts-test.env -f compose.yaml -f monitoring/compose.observability.yaml up --build --wait --wait-timeout 240
docker compose -p order-stock-alerts-dev --env-file .local/alerts-test.env -f compose.yaml -f monitoring/compose.observability.yaml exec -T -w /etc/prometheus/tests prometheus promtool test rules alerts.test.yaml
$env:ALERT_DISPOSABLE = 'true'
python scripts/verify-alerts.py --project order-stock-alerts-dev --env-file .local/alerts-test.env --output build/alerts-report.json
Remove-Item Env:ALERT_DISPOSABLE
docker compose -p order-stock-alerts-dev --env-file .local/alerts-test.env -f compose.yaml -f monitoring/compose.observability.yaml down --volumes --remove-orphans
```

실제 검증은 앱의 정상→대기→경고→해제와 인증 401 5회·429 25회 이후 대기→경고를 확인합니다.
인증 경고의 5분 구간 해제·5xx·용량·만료 경고는 합성 시계열 단위 검증에서 확인합니다.
앱 중단 동안에도 기존 재고가 유지되고 회원·주문이 생성되지 않는지 확인합니다. 리포트에는 집계값만 저장합니다.
CI도 지속 시간과 계산 구간을 줄이지 않고 동일 규칙으로 실행합니다.

근거: [Prometheus 경고 규칙·상태](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/),
[promtool 규칙 테스트](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/).
