# 이메일 비밀번호 복구

로그인 창의 **비밀번호를 잊으셨나요?**에서 가입 이메일을 입력합니다. 메일 링크를 열어 새 비밀번호와 확인 값을 입력하면 모든 기기의 이전 접속이 종료됩니다. 자동 로그인하지 않으므로 새 비밀번호로 로그인합니다. 메일 설정이 없을 때는 요청 링크를 숨기며 `/reset.html`에서 사용 불가 안내를 보여줍니다.

## 처리와 제한

- CSRF를 검증한 요청·확인 POST는 IP별 각각 기본 분당 10회까지입니다. 초과 시 `429`와 `Retry-After`를 반환합니다.
- 등록·미등록·계정 발송 제한에 모두 같은 `202` 안내를 반환합니다. 계정 조회와 SMTP 연결은 응답 이후 별도 작업에서 처리합니다. 계정당 60초 간격, 시간당 최대 5회이며 새 발송은 이전 링크를 무효화합니다. 전송 실패도 발송 한도에 포함합니다.
- 작업자는 2개, 대기열은 100개입니다. 포화는 계정 조회 전에 `503 RESET_BUSY`를 반환합니다. 영속 큐가 아니므로 앱 종료 시 남은 요청은 유실될 수 있습니다. 수신함·스팸함을 먼저 확인하고 1분 후 다시 요청합니다.
- 32바이트 난수의 URL-safe 토큰을 사용하며 DB에는 SHA-256 해시만 저장합니다. 발급 후 정확히 15분부터 만료되고 한 번만 사용됩니다. 토큰·비밀번호·이메일을 로그나 지표 라벨에 기록하지 않습니다.
- 링크는 설정된 출처의 `/reset.html#token=…`입니다. 요청 Host로 만들지 않습니다. 브라우저는 토큰을 메모리로 옮기고 주소의 fragment를 즉시 지우며 브라우저 저장소에 보관하지 않습니다. 페이지를 새로고침했다면 메일 링크를 다시 엽니다. `no-referrer`를 적용합니다.
- 비밀번호는 8자 이상, UTF-8 72바이트 이하입니다. 사용자 잠금 아래 비밀번호·인증 버전 갱신, 토큰 소비, 모든 세션 삭제를 한 트랜잭션으로 처리합니다. 동시에 확인하면 한 건만 성공합니다. 세션 삭제 실패는 변경 전체를 롤백합니다.
- 일반 비밀번호 변경도 발급된 링크를 무효화합니다. 새 메일 요청을 꺼도 이미 발급된 유효 링크는 확인할 수 있습니다. 발급 정보는 1일 후 매시간 최대 1000행씩 정리합니다.
- SMTP 접수 결과는 `stockroom.password.reset.mail{outcome=accepted|failed}`로 기록합니다. `accepted`는 수신함 도착 보장이 아닙니다. 전송 실패 시 해당 토큰을 폐기하고 예외 종류만 기록합니다.

## 로컬 수신함

기존 개발용 환경 파일과 같은 프로젝트·포트를 유지하며 다음 overlay를 함께 사용합니다.

```powershell
docker compose --env-file .env -f compose.yaml -f compose.mail-test.yaml up --build --wait --wait-timeout 240
```

수신함은 `http://127.0.0.1:18025`입니다. 앱 포트는 `APP_PORT`(기본 18080), 수신함 포트는 `MAIL_PORT`(기본 18025)로 바꿀 수 있습니다. 메일의 출처는 `http://127.0.0.1:${APP_PORT}`이므로 같은 출처에서 로그인합니다. Mailpit은 외부 발송하지 않습니다. SMTP 1025는 컨테이너 내부에서만 열고 수신함 UI는 loopback에만 엽니다. 메일은 최대 200건·24시간이며 영속 볼륨 없이 재생성 때 사라집니다. 수신함에는 계정 복구 링크가 있으므로 외부에 공개하거나 캡처·공유하지 않습니다.

자동 브라우저 검증은 별도의 일회용 프로젝트에서 가입한 계정에만 실제 SMTP 메일을 보내고, Mailpit API로 본인 테스트 메시지를 찾아 링크를 사용합니다. 실제 사용자 비밀번호를 바꾸지 않습니다.

## 운영 SMTP 준비

기본 운영 구성은 복구 요청을 꺼 둡니다. 공개 도메인·서버와 발신자 인증을 마친 SMTP 공급자가 준비되면 `deploy/compose.prod.env`의 선택 항목에 비공개 값을 입력합니다. 예제 파일에는 실제 값을 넣지 않습니다. 공급자가 요구하는 발신자 검증·SPF/DKIM/DMARC와 발송 한도를 먼저 확인합니다.

```bash
python3 scripts/production-preflight.py --env-file deploy/compose.prod.env --smtp --output artifacts/preflight-smtp.json
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml -f deploy/compose.smtp.yaml up --build --wait --wait-timeout 240
```

이후 앱을 갱신할 때도 SMTP overlay를 함께 전달합니다. 앱 시작 시 신뢰할 HTTPS 출처·발신자·SMTP 인증·필수 STARTTLS 설정을 검사합니다. 연결·읽기·쓰기 제한은 각각 5초입니다. 이 구성은 STARTTLS 방식(기본 587)이며 implicit TLS 465 설정이 아닙니다. 환경 파일과 SMTP 비밀은 서버에서만 보관합니다.

공개 서버·도메인·SMTP 공급자가 아직 연결되지 않았습니다. 외부 수신함 도착·스팸 판정·DNS 레코드·실제 공개 HTTPS 복구 흐름은 검증 전입니다. 로컬 수신 성공을 실제 외부 메일 성공으로 표시하지 않습니다.

## 백업 복원

V22는 `password_resets`를 추가합니다. [복원 도구](../scripts/production-db.sh)는 저장된 로그인 세션과 재설정 행을 모두 비워, 백업 이후 소비한 링크가 복원으로 다시 활성화되지 않게 합니다. 복원 뒤에는 새 메일을 요청합니다. 이미지와 주문 복구 기록은 그대로 복원합니다. 실제 서버 밖 보관·복원 결과는 서버가 준비된 뒤 별도로 기록합니다.

구현 참고: [Spring Boot 메일 설정](https://docs.spring.io/spring-boot/4.0/reference/io/email.html), [Mailpit 공식 Docker 안내](https://mailpit.axllent.org/docs/install/docker/).
