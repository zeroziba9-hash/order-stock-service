$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local\mysql'
$testSettingsFile = Join-Path $localRoot 'spring-test.properties'
if (Test-Path -LiteralPath $testSettingsFile) {
    Write-Output 'Isolated test database settings already exist; credentials were preserved.'
    return
}
$runtime = Get-Content -LiteralPath (Join-Path $localRoot 'runtime.json') -Raw | ConvertFrom-Json
$mysqlExe = Join-Path $runtime.installDirectory 'bin\mysql.exe'
$rootClientFile = Join-Path $localRoot 'root-client.ini'
$bytes = New-Object byte[] 32
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
$testPassword = 'Test_' + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

# SQL is passed on stdin so account credentials do not appear in process arguments.
$setupSql = @"
CREATE DATABASE IF NOT EXISTS order_stock_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'order_test'@'localhost' IDENTIFIED BY '$testPassword';
CREATE USER IF NOT EXISTS 'order_test'@'127.0.0.1' IDENTIFIED BY '$testPassword';
ALTER USER 'order_test'@'localhost' IDENTIFIED BY '$testPassword';
ALTER USER 'order_test'@'127.0.0.1' IDENTIFIED BY '$testPassword';
GRANT ALL PRIVILEGES ON order_stock_test.* TO 'order_test'@'localhost';
GRANT ALL PRIVILEGES ON order_stock_test.* TO 'order_test'@'127.0.0.1';
"@
$setupSql | & $mysqlExe "--defaults-extra-file=$rootClientFile"
if ($LASTEXITCODE -ne 0) { throw 'Test database setup failed.' }
$testProperties = @"
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/order_stock_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED
spring.datasource.username=order_test
spring.datasource.password=$testPassword
"@
[IO.File]::WriteAllText($testSettingsFile, $testProperties, [Text.UTF8Encoding]::new($false))
Write-Output 'Created isolated MySQL test DB: order_stock_test / order_test.'
