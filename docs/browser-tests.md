# 실제 브라우저 자동 테스트

`e2e/`의 Playwright 1.64.0 테스트는 실제 Spring Boot·MySQL과 Chromium으로 실행합니다.
의존성은 package-lock.json으로 고정합니다. 런타임 앱에는 Node.js나 Playwright가 포함되지 않습니다.
[Playwright 공식 CI 안내](https://playwright.dev/docs/ci)의 설치·실행 흐름을 따릅니다.

## 검증 범위

데스크톱 1280×900과 모바일 375×812에서 각각 일반 7개·관리자 7개, 총 28개 시나리오를 실행합니다.
이후 테스트 앱만 5초 마감·1초 주기로 재시작하고 [자동 만료](order-expiration.md) 시나리오를 각 1개씩 추가해 총 30개를 검증합니다.

- 공개 상품·재고 조회, 익명 주문의 로그인 안내, 가로 넘침 검사.
- 실제 회원가입·로그인, 수량별 가격, 취소 확인창의 돌아가기와 취소 후 재고 복구.
- 모의 결제·전액 환불, 상태·이력·재고 복구.
- 별도 브라우저 세션의 다른 회원에게 주문·결제·환불 조회를 404로 차단.
- 서버에 주문이 실제 커밋된 후 응답만 끊고, 재시도 시 같은 요청 키로 주문 한 건·재고 한 번 차감 확인.
- 로그아웃으로 세션이 사라진 후 로그인 만료 안내 확인.
- 상품 API 연결 실패 메시지와 새로고침으로 복구 확인.
- 일반 회원의 관리자 화면·API 차단, 관리자 확인창 취소·조정·이력·사유 안전 표시.
- 재고 조정 응답 유실 뒤 새로고침과 같은 키 재확인, 이미 열린 관리자 세션의 권한 회수.
- 상품 등록·가격 변경·판매 중지 후 공개 목록/새 주문 차단, 전체 원장과 잔액 대조.
- 상품 등록 응답 유실 후 새로고침·같은 키로 한 상품만 생성, 오래된 수정 충돌 후 최신 값 조회.

정상 데이터는 API 응답을 꾸미지 않습니다. 통신 실패 시나리오에서만 네트워크를 의도적으로 끊습니다.
자동 재시도는 0회입니다. 실패한 테스트를 재실행으로 숨기지 않습니다.
한 worker가 실행하며 계정은 테스트마다 새로 생성합니다. 후처리는 해당 계정의 주문만 취소·환불합니다.

## 로컬 실행 (PowerShell)

Node.js 22 이상, Python 3, Docker Desktop, 기존 `.env`를 준비한 뒤 저장소 루트에서 실행합니다.
아래 이름은 **테스트용으로만 사용하는 새 프로젝트**입니다. 기존 앱의 프로젝트·볼륨을 지정하지 마세요.

```powershell
$env:APP_PORT = '18083'
$env:COMPOSE_PROJECT_NAME = 'order-stock-browser-dev'
$env:E2E_ENV_FILE = (Resolve-Path .env).Path
# python3로 실행할 수 없다면 실제 Python 실행 파일을 지정합니다.
$env:E2E_PYTHON = 'python'
docker compose --project-name order-stock-browser-dev --env-file .env up --build --wait --wait-timeout 240
Set-Location e2e
npm ci
npx playwright install chromium
$env:E2E_DISPOSABLE = 'true'
npm test
```

테스트 설정이 `E2E_DISPOSABLE=true`, loopback, HTTP 18083 포트를 확인하고 다른 대상을 거부합니다.
평소 이용하는 18080 서비스는 대상이 아닙니다. Linux도 같은 Compose 옵션·환경 변수로 실행합니다.
Linux에서 브라우저 시스템 의존성이 없다면 `npx playwright install --with-deps chromium`이 필요합니다.

자동 만료 테스트까지 실행하려면 위 일반 테스트 후 저장소 루트에서 동일한 테스트 프로젝트의 앱만 재시작합니다.

```powershell
Set-Location ..
docker compose --project-name order-stock-browser-dev --env-file .env -f compose.yaml -f .github/compose.expiration-test.yaml up --no-build --no-deps --wait --wait-timeout 120 app
Set-Location e2e
$env:E2E_EXPIRATION = 'true'
npm test
Remove-Item Env:E2E_EXPIRATION
```

이 단계는 실제 DB·스케줄러·브라우저를 사용합니다. 일반 앱이나 운영 프로젝트에는 짧은 마감 override를 적용하지 않습니다.

보고서는 `npm run report`로 확인합니다. 테스트가 끝나면 저장소 루트에서 방금 만든 테스트 환경만 정리합니다.

```powershell
Set-Location ..
docker compose --project-name order-stock-browser-dev --env-file .env down --volumes --remove-orphans
Remove-Item Env:APP_PORT
Remove-Item Env:E2E_DISPOSABLE
Remove-Item Env:COMPOSE_PROJECT_NAME, Env:E2E_ENV_FILE, Env:E2E_PYTHON
```

## CI와 산출물

[Browser E2E CI](../.github/workflows/browser.yml)는 push·PR마다 잠금 파일로 설치하고 Chromium을 준비한 후
일회용 무작위 DB 비밀번호·별도 프로젝트로 앱을 빌드합니다. 완료 후 해당 테스트 볼륨을 정리합니다.
관리자 테스트는 공개 API로 새 회원을 등록하고 일회용 DB에서만 역할 도구를 실행합니다.
테스트 계정의 수동 조정은 같은 계정의 반대 조정으로 복구하며 이력은 보존합니다.
해당 테스트가 만든 상품은 후처리에서 판매 중지하고 데이터와 원장은 보존합니다.
HTML 보고서와 실패 화면만 7일 보관합니다. 비밀번호·세션 쿠키·CSRF 토큰이 들어갈 수 있는
네트워크 trace, 인증 storageState, 동영상은 저장하거나 업로드하지 않습니다.
이는 Chromium 데스크톱·모바일 크기 검증이며 실제 iOS Safari·Android 기기 검증을 뜻하지 않습니다.
