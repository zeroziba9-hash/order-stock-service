# 반복 가능한 주문 차감 방식 비교

`scripts/benchmark-orders.py`는 기본 상품 행 잠금과 실험용 조건부 UPDATE를 같은 HTTP 주문 API로 비교합니다.
기본 설정은 `locking`이며 `conditional`은 `test` 또는 `benchmark` 프로필에서만 허용하고 `prod`에서는 거부합니다.
공개 API로 전략을 바꿀 수 없습니다. 비교 결과가 생기기 전에는 성능 개선을 주장하지 않습니다.

## 비교 범위

- 기본 방식: 상품 잠금 조회 → 판매 여부·수량 확인 → 차감 → 주문·원장 저장.
- 실험 방식: `UPDATE products SET stock=stock-? WHERE id=? AND selling=TRUE AND stock>=?` → 잠금 조회·JPA refresh → 주문·원장 저장.
- 실험 방식도 상품 정보·정확한 전후 수량을 읽는 잠금 조회를 유지합니다. 잠금 없는 알고리즘이나 단일 SQL 주문 생성 비교가 아닙니다.
- 재고 부족 응답을 섞지 않고 **모든 측정 주문이 성공하도록 충분한 재고**를 준비합니다.
- 단일 상품 집중·여러 상품 분산, 동시성 1·8·32, 각 조건 3회, 회차별 실행 순서 교대, 각 조건 별도 워밍업을 기본값으로 사용합니다.
- 로그인·상품 생성·입고·DB 확인·주문 취소·컨테이너 재기동은 요청 지연 시간에서 제외합니다.

성공 HTTP 상태뿐 아니라 주문 ID 중복 없음·실제 원장 주문 수·상품별 기대 잔액·취소 후 원래 수량을 확인합니다.
잘못된 응답이나 불일치가 있으면 성능 보고서를 완성하지 않습니다.
기존 초과 판매·동일 키·반복 취소 부하 검증은 [기존 부하 테스트](load-testing.md)를 계속 사용합니다.
GitHub Backend CI는 두 차감 방식에서 전체 동시성·멱등·실패 롤백·권한 테스트를 각각 실행합니다.

## 로컬 재현

Docker와 Python 3를 준비하고 기존 앱과 다른 **새 일회용 프로젝트**를 사용합니다.
`.env.example`을 `.local/benchmark.env`로 복사한 뒤 서로 다른 임의 DB 비밀번호 두 개와 `APP_PORT=18088`을 저장합니다.
환경 파일을 커밋하거나 평소 사용하는 DB 프로젝트를 지정하지 않습니다.

```powershell
docker compose -p order-stock-benchmark-dev --env-file .local/benchmark.env -f compose.yaml -f benchmark/compose.benchmark.yaml up --build --wait --wait-timeout 240
$env:BENCHMARK_DISPOSABLE = 'true'
python scripts/benchmark-orders.py --project order-stock-benchmark-dev --env-file .local/benchmark.env --output build/paired-benchmark.json
```

스크립트는 loopback HTTP 18088·프로젝트 접두사·환경 파일 포트·빈 회원/주문 DB를 확인합니다.
기존 데이터를 초기화하지 않습니다. 각 모드에서 지정한 일회용 앱만 재시작하며 DB는 유지하고 측정 상품을 새로 만듭니다.
앱 컨테이너에 실제 설정된 모드를 확인하며, 종료 시 기본 locking으로 복원합니다.
측정 상품·취소된 주문·원장은 일회용 DB에 남고 비밀번호·쿠키·요청 키는 결과 JSON에 넣지 않습니다.

```powershell
docker compose -p order-stock-benchmark-dev --env-file .local/benchmark.env -f compose.yaml -f benchmark/compose.benchmark.yaml down --volumes --remove-orphans
Remove-Item Env:BENCHMARK_DISPOSABLE
```

## 결과와 해석

결과 JSON은 각 실행의 p50·p95·p99·최대 지연·처리량·응답 상태 수·MySQL 행 잠금 대기 변화와 검증 결과를 포함합니다.
조건별 요약은 **실행별 p95의 중앙값**이며 모든 요청을 합친 p95가 아닙니다.
소스 커밋·작업 트리 변경 여부·이미지 ID·Python·OS·Docker CPU/메모리를 기록합니다.
같은 컴퓨터의 단일 클라이언트, 짧은 closed-loop 반복 실험입니다. 운영 최대 처리량이나 통계적 유의성을 입증하지 않습니다.

CI는 동시성 4·상품 2·요청 64·워밍업 32·3회로 실험 절차와 잔액 검증의 회귀를 검사합니다.
성능 수치의 임계값을 CI 성공 조건으로 두지 않습니다. `paired-order-benchmark` artifact는 7일 보관합니다.
실제 측정 결과와 기본 전략 유지 판단은 실행 후 별도 결과 절에 기록합니다.

참고: [MySQL 잠금 조회](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html),
[UPDATE](https://dev.mysql.com/doc/refman/8.4/en/update.html).
