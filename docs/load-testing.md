# HTTP 동시 요청과 재고 정합성 측정

실제 Spring Boot·MySQL 컨테이너에 HTTP 요청을 보내 응답과 DB 결과를 함께 확인합니다.
`scripts/load-orders.py`는 Python 3.10 이상 표준 라이브러리만 사용합니다.
공유 재고를 초기화하지 않으며, 비어 있는 별도 Compose DB와 loopback 18084 포트에서만 실행합니다.

## 확인하는 세 가지 조건

각 단계는 100회 요청합니다. 단계 사이 인증·조회·정리는 측정에서 제외합니다.

1. 초기 재고 10개에 서로 다른 요청 키로 주문: `201` 10개, 재고 부족 `409` 90개, 남은 재고 0, DB 주문 10개.
2. 한 회원이 같은 키로 주문 재시도: 새 주문 `201` 1개, 기존 주문 반환 `200` 99개, 같은 주문 ID, 재고 1개만 차감.
3. 같은 주문 반복 취소: `200` 100개, 최초 취소 시각 유지, 재고 1개만 복구.

성공 응답만 세지 않고 본인 주문 목록·실제 DB 주문 수·상품 재고를 비교합니다.
일회용 DB인지 확인한 뒤 회원을 만들며, 쿠키·비밀번호·회원 이메일·요청 키를 결과 파일에 기록하지 않습니다.

## 로컬 재현

Docker Compose와 Python을 준비하고 저장소 루트의 PowerShell에서 실행합니다.
`.env`는 [Docker 안내](docker.md)에 따라 먼저 준비합니다. 아래 프로젝트 이름은 실행마다 새로 생성합니다.
기존 앱의 18080 포트·DB 볼륨을 사용하지 않습니다.

```powershell
$project = 'order-stock-load-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$previousPort = $env:APP_PORT
$previousFlag = $env:LOAD_DISPOSABLE
$env:APP_PORT = '18084'
$env:LOAD_DISPOSABLE = 'true'
try {
    docker compose -p $project --env-file .env up --build --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw 'Test stack failed to start' }
    python scripts/load-orders.py --project $project --env-file .env --concurrency 32 --requests 100 --output build/load-report.json
    if ($LASTEXITCODE -ne 0) { throw 'Concurrency verification failed' }
} finally {
    # 이 실행에서 만든 테스트 프로젝트의 볼륨만 제거합니다.
    docker compose -p $project --env-file .env down --volumes --remove-orphans
    $env:APP_PORT = $previousPort
    $env:LOAD_DISPOSABLE = $previousFlag
}
```

동시성을 바꿀 때도 전체 블록을 다시 실행해 새 DB를 사용합니다. 지원 범위는 동시성 1~64, 요청 32~1000입니다.
기존 회원·주문이 있으면 거부합니다. 테스트 종료 뒤 위 테스트 볼륨은 필요하지 않습니다.
운영 Compose의 볼륨에 `down --volumes`를 사용하지 않습니다.

[Order Concurrency CI](../.github/workflows/load.yml)는 무작위 일회용 DB 비밀번호로 같은 검증을 실행하고
집계 JSON만 7일간 보관합니다. 이 테스트는 합격 조건을 응답 속도에 두지 않습니다.

## 측정 결과: 기본 연결 풀 10

2026-10-13 KST, 앱 소스 `9d782cfab0d5a5d3569682d80c61709922b7a5d9`, Java 17·MySQL 8.4,
Docker Desktop/WSL 2, 엔진이 보고한 CPU 16개·메모리 약 15.4 GiB,
Windows Python 3.12.14 클라이언트에서 측정했습니다. 기존 로컬 앱도 실행된 상태이며 전용 벤치마크 머신이 아닙니다.
각 행은 새 DB·새 앱에서 한 번 측정한 결과입니다. Hikari 최대 연결 수는 테스트 override로 10을 명시했습니다.

| 동시 요청 수 | 주문 p95 (ms) | 주문 처리량 (req/s) | 주문 단계 DB 잠금 대기 횟수 | DB 잠금 누적 대기 (ms) | 재시도 p95 (ms) | 취소 p95 (ms) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 32.99 | 61.29 | 0 | 0 | 31.85 | 27.39 |
| 8 | 88.94 | 267.21 | 63 | 791 | 38.00 | 28.14 |
| 32 | 178.87 | 189.17 | 96 | 1363 | 113.42 | 48.93 |

원본 집계: [동시성 1](benchmarks/baseline-1.json), [8](benchmarks/baseline-8.json), [32](benchmarks/baseline-32.json).
세 경우 모두 세 단계의 정합성 조건을 통과했습니다.

## 연결 풀 비교 실험

동시성 32에서 테스트 override의 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`만 20으로 바꾸고
새 DB·새 앱에서 동일한 100회 요청을 실행했습니다. 다른 애플리케이션 설정은 바꾸지 않았습니다.

| 최대 DB 연결 | 주문 p95 (ms) | 주문 처리량 (req/s) | 재시도 p95 (ms) | 취소 p95 (ms) |
|---:|---:|---:|---:|---:|
| 10 | 178.87 | 189.17 | 113.42 | 48.93 |
| 20 | 241.14 | 128.93 | 103.03 | 46.87 |

[연결 풀 20 집계](benchmarks/pool20-32.json)에서도 정합성 조건은 모두 통과했습니다.
주문 단계의 개선은 관측되지 않았습니다. 각 조건을 한 번 실행했으므로 통계적 우열이나 인과관계를 주장하지 않습니다.
기본 운영 설정을 변경할 근거로 사용하지 않았습니다.
재현하려면 테스트 Compose override에서 위 환경 변수만 지정하고 매번 새 프로젝트로 실행합니다.

## 해석과 다음 실험

동시성 32에서 처리량이 8보다 낮고 p95와 InnoDB 잠금 대기가 증가했습니다.
같은 상품 행을 잠그는 구현에서 경합이 관측됐다는 근거입니다.
연결 풀 대기·JIT·GC·클라이언트 스케줄링은 따로 계측하지 않았으므로 잠금만을 유일한 원인으로 단정하지 않습니다.
`Innodb_row_lock_time`은 여러 트랜잭션의 누적값이어서 단계의 실제 경과 시간보다 클 수 있습니다.

요청 수가 작고 별도 워밍업이 없는 closed-loop 테스트입니다. 작업자마다 다음 요청은 이전 응답 뒤에 전송합니다.
HTTP 호출 시간에서 스레드 큐 대기는 제외하며, 처리량은 작업자 시작을 포함한 단계 전체 경과 시간으로 계산합니다.
백분위는 nearest-rank입니다. 주문 처리량에는 재고 부족 90건도 포함되므로 성공 주문 처리량이나 운영 최대 용량을 뜻하지 않습니다.

성능 개선을 판단하려면 충분한 재고·여러 상품·충분한 실행 시간·반복 측정·워밍업을 추가하고
Hikari 대기 시간과 서버 CPU·쿼리 시간을 함께 수집해야 합니다.
조건부 재고 UPDATE로 바꾸는 실험은 주문·멱등 키·취소의 같은 정합성 검증을 통과하는 별도 PR에서 비교합니다.
현재는 검증 없이 잠금 구현이나 운영 연결 풀 값을 변경하지 않습니다.

## 후속 비교

재고 부족 응답을 제외하고 충분한 재고에서 두 차감 방식을 비교하는 워밍업·반복 실험은
[차감 방식 비교](paired-benchmark.md)를 참고하세요. 위 초과 판매 검증과 측정 목적이 다릅니다.
