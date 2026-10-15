# 기능 브랜치와 PR로 작업하기

`main`은 검증된 코드를 모으는 기준 브랜치로 사용합니다. 새 변경은 작업 목적에 따라 짧게 유지하는 브랜치에서 진행합니다.
병합된 브랜치를 삭제해서 GitHub에 `main` 하나만 남는 것은 정상입니다. 브랜치 개수보다 PR의 변경 내용·검증 기록이 중요합니다.
기존 main 이력을 억지로 재작성하거나 과거 기능에 빈 브랜치를 만들지 않습니다.

## PR별 작업 기록

| 작업 | 범위 | 검토 |
|---|---|---|
| `feat/browser-e2e` | 데스크톱·모바일 브라우저 테스트 | [PR #1](https://github.com/zeroziba9-hash/order-stock-service/pull/1) |
| `feat/load-testing` | HTTP 동시 요청·실제 DB 결과·측정 | [PR #2](https://github.com/zeroziba9-hash/order-stock-service/pull/2) |
| `feat/deployment-verification` | HTTPS 배포 결과 점검·CI·운영 기록 | [PR #3](https://github.com/zeroziba9-hash/order-stock-service/pull/3) |
| 설계·검증 문서 | README·아키텍처·설계 근거·검증 문서 | [PR #4](https://github.com/zeroziba9-hash/order-stock-service/pull/4) |
| `feat/order-expiration` | 미결제 주문 자동 만료·V8→V9 업그레이드·브라우저 검증 | [PR #5](https://github.com/zeroziba9-hash/order-stock-service/pull/5) |
| `feat/verified-api-docs` | 테스트 기반 REST Docs·API 계약·마스킹 검증 | [PR #6](https://github.com/zeroziba9-hash/order-stock-service/pull/6) |
| `feat/observability` | Prometheus·Grafana·운영 계측 | [PR #7](https://github.com/zeroziba9-hash/order-stock-service/pull/7) |
| `feat/admin-inventory` | 관리자 권한·수동 재고 조정·변경 이력·UI | [PR #8](https://github.com/zeroziba9-hash/order-stock-service/pull/8) |
| `feat/catalog-ledger-benchmarks` | 상품 등록·수정·판매 중지, 전체 재고 원장, 차감 방식 반복 비교 | [PR #9](https://github.com/zeroziba9-hash/order-stock-service/pull/9) |
| `feat/auth-request-limits` | 인증 요청 제한·동시 검사 예약·재시도 화면·인증 지표 | [PR #10](https://github.com/zeroziba9-hash/order-stock-service/pull/10) |
| `feat/catalog-change-history` | 상품 수정 전후·관리자·시각, 트랜잭션·커서·이력 화면 | [PR #11](https://github.com/zeroziba9-hash/order-stock-service/pull/11) |
| `feat/monitoring-alert-rules` | 경고 5개·상태 패널·대응 문서·실제 앱 중단/복구·인증 폭주 검증 | [PR #12](https://github.com/zeroziba9-hash/order-stock-service/pull/12) |
| `feat/request-tracing` | 서버 발급 요청 ID·보안 필터 차단 추적·안전한 오류 로그 | [PR #13](https://github.com/zeroziba9-hash/order-stock-service/pull/13) |
| `docs/project-documentation` | 프로젝트 소개·설계 문서·API 안내·화면 문구 | [PR #14](https://github.com/zeroziba9-hash/order-stock-service/pull/14) |

현재 `docs/project-documentation`에서 프로젝트 소개·설계 문서·화면 문구를 정리합니다.
PR #13은 PR #11까지 병합된 main에서 시작했으며, PR #12 병합 후 최신 main을 기능 브랜치에 병합해 문서 충돌을 해결했습니다.

위 PR #1~#13은 main에 병합됐습니다. 초기 #1~#4는 같은 main 커밋에서 독립적으로 시작했고,
이후 기능도 최신 병합 main에서 분기합니다. 미병합 코드를 다음 기능에 섞지 않습니다.
PR #8은 PR #6 기준으로 시작했으며, PR #7 병합 후 최신 main을 기능 브랜치에 병합해 충돌을 해결했습니다.

## 새 작업 시작

작업 트리가 깨끗한지 먼저 확인하고 원격 main을 갱신합니다. 아래 이름은 예시이므로 실제 작업에 맞게 정합니다.

```bash
git status
git fetch origin
git switch -c feat/product-search origin/main
```

변경한 파일을 확인하고 해당 기능에 필요한 테스트만 실행합니다. 비밀번호·환경 파일·DB 데이터·개인 정보는 올리지 않습니다.
관련된 파일만 명시하여 커밋하고 원격 기능 브랜치로 푸시합니다.

```bash
git diff
git add path/to/changed-file
git diff --cached --check
git diff --cached
git commit -m "feat: add product search"
git push -u origin feat/product-search
```

GitHub에서 base를 `main`, compare를 작업 브랜치로 지정해 PR을 만듭니다.
문제·변경 후 동작·실행한 검증·남은 한계를 적고 Files changed와 CI를 확인합니다.
CI가 통과하고 내용을 검토한 뒤 병합합니다. 자동으로 main에 푸시하거나 PR을 병합하지 않습니다.

## 병합 후

Squash merge를 사용하면 기능 단위 커밋으로 main을 정리할 수 있습니다. 병합 전 PR에는 세부 수정 이력이 남습니다.
브랜치에 남은 작업이 없는지 확인하고 GitHub에서 병합된 원격 브랜치를 삭제할 수 있습니다.
다음 기능은 갱신한 `origin/main`에서 다시 분기합니다. 혼자 진행하는 현재 규모에는 장기 `develop` 브랜치가 필수는 아닙니다.

저장소 설정의 branch protection 또는 ruleset에서 main 직접 푸시 제한과 필수 CI를 설정하면 이 규칙을 강제할 수 있습니다.
이 문서를 추가했다고 저장소 보호 설정까지 활성화되는 것은 아닙니다.
