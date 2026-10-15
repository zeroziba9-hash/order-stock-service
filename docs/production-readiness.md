# 공개 배포 준비와 릴리스 확인

아직 공개 서버·도메인은 연결하지 않았습니다. 이 문서는 실행 가능한 준비 절차이며 공개 배포·서버 밖 복구 완료 기록이 아닙니다.
기존 [HTTPS 구성](https-deployment.md)과 [백업·복원 도구](backup-restore.md)를 함께 사용합니다.

## 서버가 준비되면

Linux 서버에 Docker Compose·Python 3와 저장소의 검증된 커밋을 준비합니다.
본인 DNS가 서버의 공개 주소를 가리키고 80·443 포트가 연결돼 있어야 합니다. 앱·MySQL 포트는 공개하지 않습니다.
서버 종류·OS·도메인만 공유하면 되며 비밀번호·개인키를 채팅이나 Git에 넣지 않습니다.

```bash
cp deploy/compose.prod.env.example deploy/compose.prod.env
chmod 600 deploy/compose.prod.env
```

파일을 편집기로 열어 `APP_DOMAIN`과 서로 다른 무작위 DB 비밀번호 두 개를 입력합니다.
비밀번호는 24자 이상 영문·숫자·밑줄·하이픈을 사용하며 예제 값을 재사용하지 않습니다.
인증 프록시 신뢰 주소는 실제 확인한 Caddy 주소만 지정합니다.

```bash
python3 scripts/production-preflight.py \
  --env-file deploy/compose.prod.env --check-dns \
  --output /var/backups/stockroom/preflight.json
```

사전 검사는 파일 권한·도메인·비밀번호 분리·Compose prod·모의 쓰기 보호·프록시 포트·앱/DB 비공개와 DNS를 확인합니다.
컨테이너를 시작하거나 DB를 변경하지 않으며 환경 파일과 비밀번호를 출력하지 않습니다.
`publicDeploymentVerified`와 `externalBackupRestoreVerified`는 항상 false입니다. DNS 옵션을 생략하면 오프라인 설정만 검사합니다.

## 배포와 외부 확인

업그레이드라면 먼저 현재 DB의 백업·체크섬을 만들고 서버 밖에 별도 암호화 보관합니다.
복구 검증은 원본 운영 DB가 아닌 새 빈 복구 프로젝트에서 수행합니다. 구체 명령은 [복원 안내](backup-restore.md)를 따릅니다.

```bash
docker compose --env-file deploy/compose.prod.env -f deploy/compose.prod.yaml up --build --wait --wait-timeout 240
python3 scripts/verify-deployment.py --url https://본인도메인 \
  --output /var/backups/stockroom/deployment-check.json
```

외부 네트워크에서도 동일 GET 검사를 실행해 공개 인증서·HTTP→HTTPS·정적 화면·준비 상태·상품 조회·익명 주문/장바구니 거부·운영 모의 차단을 확인합니다.
테스트 계정으로 로그인·계정 장바구니·최신 가격 확인·여러 상품 주문·취소를 확인하고 해당 계정의 재고 복구를 확인합니다.
운영 모의 결제·환불은 계속 차단되며 실제 결제사는 연결하지 않습니다.

릴리스 기록에는 커밋 SHA·이미지 ID·UTC 배포 시각·Flyway 버전·외부 검사 결과·서버 밖 복구 결과만 남깁니다.
회원 이메일·쿠키·CSRF·비밀번호·원본 SQL·백업 파일을 공개 아티팩트로 올리지 않습니다.

## V16 이후 실패 대응

V16은 주문 요청 완료 연결을 새 표로 옮기고 이전 `order_id` 열을 제거합니다.
따라서 V16 이전 앱 이미지를 현재 DB에 바로 연결하는 롤백은 허용하지 않습니다.
앱 시작·준비 상태 검사에 실패하면 트래픽 연결을 중지하고, 로그의 추적 ID·오류 유형과 Flyway 상태를 확인합니다.
호환되는 수정 버전으로 진행하거나 배포 전 백업을 **별도 새 빈 DB**에 복구하고 그 백업과 호환되는 검증 이미지로 기동합니다.
새 DB에서 업무 데이터·원장·로그인·쿠키 회수 정책을 확인한 뒤 트래픽을 전환합니다.
백업 이후의 데이터 손실 범위와 재로그인 필요 여부를 확인하며 운영 볼륨 삭제·Flyway 이력 삭제로 되돌리지 않습니다.
