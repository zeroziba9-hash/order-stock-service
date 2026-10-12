$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local\mysql'
$runtimePath = Join-Path $localRoot 'runtime.json'
if (-not (Test-Path -LiteralPath $runtimePath)) {
    throw 'MySQL local configuration is missing.'
}
$runtime = Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json
$mysqlExe = Join-Path $runtime.installDirectory 'bin\mysql.exe'
$mysqldExe = Join-Path $runtime.installDirectory 'bin\mysqld.exe'
$clientFile = Join-Path $localRoot 'app-client.ini'
$serverFile = Join-Path $localRoot 'my.ini'

# Use a real authenticated query to check this project's database.
$oldNativePreference = $PSNativeCommandUseErrorActionPreference
$oldErrorPreference = $ErrorActionPreference
$PSNativeCommandUseErrorActionPreference = $false
$ErrorActionPreference = 'Continue'
try {
    & $mysqlExe "--defaults-extra-file=$clientFile" --batch --skip-column-names --execute='SELECT 1;' 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Output 'MySQL is already running: 127.0.0.1:3306 / order_stock'
        return
    }
} finally {
    $PSNativeCommandUseErrorActionPreference = $oldNativePreference
    $ErrorActionPreference = $oldErrorPreference
}

$listener = Get-NetTCPConnection -LocalPort $runtime.port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "Port $($runtime.port) is in use by a different server. It was left unchanged."
}

$server = Start-Process -FilePath $mysqldExe -ArgumentList @("--defaults-file=`"$serverFile`"") -WorkingDirectory $localRoot -WindowStyle Hidden -PassThru
$oldNativePreference = $PSNativeCommandUseErrorActionPreference
$oldErrorPreference = $ErrorActionPreference
$PSNativeCommandUseErrorActionPreference = $false
$ErrorActionPreference = 'Continue'
try {
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($server.HasExited) {
            throw "MySQL exited. Check $localRoot\server.err"
        }
        & $mysqlExe "--defaults-extra-file=$clientFile" --batch --skip-column-names --execute='SELECT 1;' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Output 'MySQL started: 127.0.0.1:3306 / order_stock'
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "MySQL did not become ready. Check $localRoot\server.err"
} finally {
    $PSNativeCommandUseErrorActionPreference = $oldNativePreference
    $ErrorActionPreference = $oldErrorPreference
}
