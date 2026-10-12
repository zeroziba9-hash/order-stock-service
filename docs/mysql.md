# 로컬 MySQL 사용법

MySQL Community Server 8.4.9 LTS를 Windows에 설치했습니다.
프로젝트 데이터와 접속 설정은 `.local/mysql`에 보관합니다.

## 접속 정보

| 항목 | 값 |
|---|---|
| 호스트 | `127.0.0.1` |
| 포트 | `3306` |
| DB | `order_stock` |
| 개발 계정 | `order_app` |

비밀번호는 자동 생성되어 로컬 접속 파일에 저장됩니다.
아래 스크립트는 접속 파일을 사용하므로 비밀번호를 입력할 필요가 없습니다.
`.local/`은 Git에서 제외되어 있습니다.

## 시작

프로젝트 폴더의 PowerShell 터미널에서 실행합니다.

```powershell
.\scripts\start-db.ps1
```

MySQL은 별도 백그라운드 프로세스로 실행됩니다. PC를 재부팅한 뒤에는 이 명령으로 다시 시작합니다.

## 접속 확인

```powershell
.\scripts\db.ps1 -Query 'SELECT VERSION(), DATABASE(), CURRENT_USER();'
```

직접 SQL을 입력하려면:

```powershell
.\scripts\db.ps1
```

MySQL 프롬프트에서:

```sql
SHOW TABLES;
exit;
```

Spring 서버를 처음 실행하면 Flyway가 `products` 테이블과 샘플 상품을 생성합니다.
두 번째 실행부터는 적용 이력을 확인하므로 샘플 상품이 중복 삽입되지 않습니다.

```powershell
.\scripts\db.ps1 -Query 'SELECT id, name, price, stock FROM products ORDER BY id;'
```

## 종료

```powershell
.\scripts\stop-db.ps1
```

종료해도 저장된 데이터는 유지됩니다.

## Spring 연결

데이터소스 설정은 `.local/mysql/spring-local.properties`에서 자동으로 읽습니다.
JPA가 상품을 조회하며, 테이블 변경은 Flyway SQL 마이그레이션으로 관리합니다.

## 테스트 DB

```powershell
.\scripts\setup-test-db.ps1
.\gradlew.bat test
```

`order_stock_test`와 전용 `order_test` 계정을 사용합니다.
테스트 계정에는 개발 DB인 `order_stock`의 권한을 부여하지 않습니다.
접속 파일 `.local/mysql/spring-test.properties`도 Git에서 제외됩니다.

공식 설치 문서: https://dev.mysql.com/doc/refman/8.4/en/windows-installation.html
