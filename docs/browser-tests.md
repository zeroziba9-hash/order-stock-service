# 실제 브라우저 자동 테스트

`e2e/`의 Playwright 1.64.0 테스트는 실제 Spring Boot·MySQL·Mailpit과 Chromium·Firefox·WebKit으로 실행합니다.
의존성은 package-lock.json으로 고정합니다. 런타임 앱에는 Node.js나 Playwright가 포함되지 않습니다.
[Playwright 공식 CI 안내](https://playwright.dev/docs/ci)의 설치·실행 흐름을 따릅니다.

## 검증 범위

데스크톱 1280×900과 모바일 375×812에서 각각 일반 7개·관리자 9개·계정 3개·검색/장바구니/운영 조회 4개·가격 확인/계정 장바구니 9개·상품 상세 5개·이미지/주문 복구 9개·메일 복구/콘텐츠 점검 4개, 엔진별 총 100개 시나리오를 실행합니다.
이후 테스트 앱만 5초 마감·1초 주기로 재시작하고 [자동 만료](order-expiration.md) 시나리오를 각 1개씩 추가합니다. 마지막으로 인증 제한용 앱 설정으로 재시작해 각 3개씩 추가하여 엔진별 108개, 세 엔진 총 324개를 검증합니다.

- 실제 SMTP 수신·링크 사용·동일 탭에서 메일 링크 열기·모든 접속 종료·이전 비밀번호 거부·토큰 재사용 거부·등록 여부와 동일한 응답·재발송 한도·빈 콘텐츠·긴 상품명·품절·판매 중지.
- 이미지 미리보기·업로드·삭제·오래된 교체 거부·이미지 오류 fallback, 탭 저장소 삭제 후 완료 결과 확인·다른 세션의 미완료 요청 재개·닫기·손상된 응답 재시도·복구 조회 실패·확인 가격 변경 안내 유지.
- 상품 상세 직접 접속·새로고침·수량 선택·로그인 후 이어 담기·가격 확인 주문·통신 실패 재시도·판매 중지·설명 안전 표시·편집 충돌·늦은 응답 무시.
- 다른 접속의 계정 장바구니·저장소 삭제 후 유지·수정 충돌·최신 가격 표시·확인 중 가격 변경 거부·판매 중지·다른 기기 수정 보존·이전 보류 주문 재시도.
- 내 계정 로그인 안내·본인 접속·확인 취소·다른 접속 종료·비밀번호 변경·모든 접속 종료·새 비밀번호 로그인·조회 실패 복구.

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
- 상품 전환 중 원장 조회 실패 시 이전 상품 기록을 제거하고 새로고침으로 복구.
- 상품 등록 응답 유실 후 새로고침·같은 키로 한 상품만 생성, 오래된 수정 충돌 후 최신 값 조회.
- 로그인 실패·가입 제한·이메일 변경 시 IP 한도, 실제 429의 대기 표시·창 재열기·수동 재시도 복구.
- 상품 정보 변경 전후·no-op·21건 커서·HTML 문자 안전 표시·이전 상품의 늦은 응답 차단·통신 실패 복구.

정상 데이터는 실제 API로 생성·조회합니다. 실패 시나리오에서만 응답 유실·오류 상태·손상된 응답을 주입합니다.
자동 재시도는 0회입니다. 실패한 테스트를 재실행으로 숨기지 않습니다.
한 worker가 실행하며 계정은 테스트마다 새로 생성합니다.
일반 화면 검증에서는 전용 `.github/compose.browser-test.yaml`로 로그인·가입 테스트 계정 한도를 500회로 설정합니다. 제한기는 활성화하며 일반 서비스 설정은 수정하지 않습니다.
마지막 인증 제한 단계는 전용 auth overlay를 나중에 적용해 로그인 6회·가입 2회 등의 짧은 한도로 다시 검사합니다. 후처리는 해당 계정의 주문만 취소·환불합니다.

## 로컬 실행 (PowerShell)

Node.js 22 이상, Python 3, Docker Desktop, 기존 `.env`를 준비한 뒤 저장소 루트에서 실행합니다.
아래 이름은 **테스트용으로만 사용하는 새 프로젝트**입니다. 기존 앱의 프로젝트·볼륨을 지정하지 마세요.

```powershell
$env:APP_PORT = '18083'
$env:COMPOSE_PROJECT_NAME = 'order-stock-browser-dev'
$env:E2E_ENV_FILE = (Resolve-Path .env).Path
# python3로 실행할 수 없다면 실제 Python 실행 파일을 지정합니다.
$env:E2E_PYTHON = 'python'
docker compose --project-name order-stock-browser-dev --env-file .env -f compose.yaml -f .github/compose.browser-test.yaml -f compose.mail-test.yaml up --build --wait --wait-timeout 240
Set-Location e2e
npm ci
npx playwright install chromium firefox webkit
$env:E2E_DISPOSABLE = 'true'
$env:E2E_ENGINE = 'chromium' # firefox, webkit도 각각 실행
npm test
```

테스트 설정이 `E2E_DISPOSABLE=true`, loopback, HTTP 18083 포트를 확인하고 다른 대상을 거부합니다.
평소 이용하는 18080 서비스는 대상이 아닙니다. Linux도 같은 Compose 옵션·환경 변수로 실행합니다.
Linux에서 브라우저 시스템 의존성이 없다면 `npx playwright install --with-deps chromium firefox webkit`이 필요합니다.

자동 만료 테스트까지 실행하려면 위 일반 테스트 후 저장소 루트에서 동일한 테스트 프로젝트의 앱만 재시작합니다.

```powershell
Set-Location ..
docker compose --project-name order-stock-browser-dev --env-file .env -f compose.yaml -f .github/compose.browser-test.yaml -f compose.mail-test.yaml -f .github/compose.expiration-test.yaml up --no-build --no-deps --wait --wait-timeout 120 app
Set-Location e2e
$env:E2E_EXPIRATION = 'true'
npm test
Remove-Item Env:E2E_EXPIRATION
```

이 단계는 실제 DB·스케줄러·브라우저를 사용합니다. 일반 앱이나 운영 프로젝트에는 짧은 마감 override를 적용하지 않습니다.

인증 제한 6개는 별도 설정으로 앱을 재시작해 실행합니다. 같은 테스트 전용 프로젝트에서만 사용하세요.

```powershell
Set-Location ..
docker compose --project-name order-stock-browser-dev --env-file .env -f compose.yaml -f .github/compose.browser-test.yaml -f compose.mail-test.yaml -f .github/compose.auth-test.yaml up --no-build --no-deps --wait --wait-timeout 120 app
Set-Location e2e
$env:E2E_AUTH_LIMITS = 'true'
npm test
Remove-Item Env:E2E_AUTH_LIMITS
```

이 단계는 3초 시간 구간·로그인 6회·가입 2회·실패 2회 설정입니다. 실제 서버가 429를 반환하며 응답을 가짜로 만들지 않습니다.
보고서는 `playwright-auth-report`, 실패 화면은 `auth-results`에 저장합니다. 상세 정책은 [인증 요청 제한](auth-limits.md)을 참고하세요.

보고서는 `npm run report`로 확인합니다. 테스트가 끝나면 저장소 루트에서 방금 만든 테스트 환경만 정리합니다.

```powershell
Set-Location ..
docker compose --project-name order-stock-browser-dev --env-file .env down --volumes --remove-orphans
Remove-Item Env:APP_PORT
Remove-Item Env:E2E_DISPOSABLE
Remove-Item Env:COMPOSE_PROJECT_NAME, Env:E2E_ENV_FILE, Env:E2E_PYTHON
```

## CI와 산출물

[Browser E2E CI](../.github/workflows/browser.yml)는 push·PR마다 잠금 파일로 설치하고 세 엔진을 각각 독립된 matrix 작업에서 준비한 후
일회용 무작위 DB 비밀번호·별도 프로젝트로 앱을 빌드합니다. 완료 후 해당 테스트 볼륨을 정리합니다.
관리자 테스트는 공개 API로 새 회원을 등록하고 일회용 DB에서만 역할 도구를 실행합니다.
테스트 계정의 수동 조정은 같은 계정의 반대 조정으로 복구하며 이력은 보존합니다.
해당 테스트가 만든 상품은 후처리에서 판매 중지하고 데이터와 원장은 보존합니다.
HTML 보고서와 실패 화면만 7일 보관합니다. 비밀번호·세션 쿠키·CSRF 토큰이 들어갈 수 있는
네트워크 trace, 인증 storageState, 동영상은 저장하거나 업로드하지 않습니다.
Firefox의 모바일 프로젝트는 작은 viewport이며 `isMobile` 에뮬레이션은 사용하지 않습니다. Windows에서는 WinCairo WebKit, Linux CI에서는 해당 플랫폼의 WebKit을 사용합니다. 실제 iOS Safari·Android 기기 검증을 뜻하지 않습니다. [Playwright 브라우저 안내](https://playwright.dev/docs/browsers)를 참고하세요.

메일 수신함은 기본 loopback 18025입니다. 다른 테스트 수신함을 쓰면 `MAIL_PORT`와 `E2E_MAIL_URL`을 함께 지정합니다. Mailpit API 대상도 loopback 18025·18026만 허용합니다. [복구 정책](password-recovery.md)을 참고하세요.

## 접근성 검사

`accessibility.spec.cjs`는 고정 버전 `@axe-core/playwright`로 WCAG 2 A·AA 및 2.1 AA 태그의 자동 검사를 실행합니다.
상품·로그인·계정·장바구니·관리자·비밀번호 복구 화면의 레이블·대비를 확인하고,
본문 바로가기·제목 포커스·Tab 순환·Escape·호출 버튼 복귀·320 CSS px 배치와 텍스트 확대를 검사합니다.
일회용 관리자 계정을 사용하며 확인창은 취소해 재고·가격을 변경하지 않습니다.
검사 통과는 전체 WCAG 준수 인증이나 실제 보조 기술·기기에서의 검증을 뜻하지 않습니다.
