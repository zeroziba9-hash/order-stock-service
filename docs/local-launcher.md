# Windows 로컬 서버 실행 도구

저장소 루트의 `start-server.cmd`를 더블클릭하면 Docker Desktop 준비를 기다리고,
선택한 Compose 프로젝트를 시작한 뒤 준비 상태를 확인해 기본 브라우저를 엽니다.
Windows PowerShell 5.1과 Docker Desktop의 Linux 컨테이너를 사용합니다.

## 기존 서버 연결

`.local` 폴더에 `scripts/launcher.example.json`을 `launcher.json`으로 복사합니다.
이미 파일이 있으면 덮어쓰지 않습니다. `project`에는 **기존 Compose 프로젝트 이름**,
`envFile`에는 그 프로젝트에서 사용한 환경 파일, `composeFiles`에는 적용했던 overlay까지 순서대로 적습니다.
프로젝트 이름이 바뀌면 다른 DB 볼륨을 가리키므로 `docker compose ls`로 먼저 확인합니다.
파일 경로는 저장소 루트 기준이며 절대 경로도 사용할 수 있습니다. 암호는 JSON에 넣지 않습니다.
`.local`과 환경 파일은 Git에서 제외됩니다.

```json
{
  "version": 1,
  "project": "order-stock-service",
  "envFile": ".env",
  "composeFiles": ["compose.yaml"]
}
```

| 파일 | 동작 |
|---|---|
| `start-server.cmd` | 기존 이미지·DB로 기동하고 준비 확인 후 브라우저 열기 |
| `server-status.cmd` | 선택한 프로젝트의 컨테이너·주소·준비 상태 표시 |
| `stop-server.cmd` | 앱·DB·선택한 로컬 메일 서버만 정상 종료. 컨테이너와 데이터 유지 |

다른 위치의 터미널에서 실행해도 설정의 상대 경로는 저장소 루트를 기준으로 처리합니다.
PowerShell을 직접 사용하면 완료 상태는 종료 코드 0, 실패나 준비되지 않은 상태는 1입니다.
기본 대기 시간은 Docker 준비와 Compose 시작 단계에 각각 최대 240초입니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-server.ps1 -Action start -NoBrowser
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-server.ps1 -Action status
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-server.ps1 -Action stop
```

## 최초 설치와 코드 반영

새 설치는 [Docker 안내](docker.md)에 따라 별도 환경 파일을 준비한 뒤 프로젝트 이름을 확인합니다.
새 빈 DB를 만들 의도가 있을 때만 `-Initialize`를 명시합니다. 기존 DB 파일·암호를 생성하거나 덮어쓰지 않습니다.

```powershell
# 새 설치에서만: 빈 DB 생성과 소스 빌드
.\scripts\local-server.ps1 -Action start -Build -Initialize

# 기존 서버에 변경한 소스 반영: 데이터 유지, 앱 이미지 재빌드
.\scripts\local-server.ps1 -Action start -Build
```

기본 시작은 `--no-build`를 사용하므로 소스 변경을 자동 반영하지 않습니다.
별도 이미지 overlay가 있으면 `-Build`의 태그·pull 정책도 그 overlay를 따릅니다.
개인 PC에만 있는 사전 빌드 이미지와 overlay는 다른 PC로 자동 복사되지 않습니다.

## 범위와 실패 시 확인

- 로컬 Docker context의 `local` 앱만 지원하며 앱 포트는 `127.0.0.1`, DB 포트는 내부로 제한합니다.
- 존재하지 않는 DB 볼륨은 `-Initialize` 없이 만들지 않습니다. 잘못된 프로젝트·환경 파일을 먼저 확인합니다.
- `stop`은 볼륨 삭제나 Docker Desktop 종료를 하지 않습니다. 다른 프로젝트도 중지하지 않습니다.
- Docker 미설치, WSL 미준비, 사용 중인 포트, 빌드 실패, 준비 상태 실패는 오류로 종료합니다.
- 환경 파일과 Compose 렌더링 결과·원본 Docker 오류는 도구가 출력하지 않습니다.
- 공개 배포·운영 서버에는 이 도구 대신 [공개 배포 절차](production-readiness.md)를 사용합니다.

## 수명 주기 재현 검사

이미 빌드한 앱 이미지와 비어 있는 루프백 18086 포트를 사용해 별도의 무작위 프로젝트에서만 검사합니다.
`--disposable`은 필수이며 명시적 초기화·없는 볼륨 차단·상태·중지·재시작·반복 시작과 실제 DB 보존을 확인합니다.
끝나면 이 검사에서 생성한 일회용 컨테이너·볼륨만 제거합니다. 기존 데모는 입력받지 않습니다.

```powershell
python scripts/verify-local-launcher.py --disposable --image stockroom:0.1.0-rc.1 --output build/launcher-report.json
```
