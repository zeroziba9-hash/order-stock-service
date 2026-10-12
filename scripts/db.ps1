param([string]$Query)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local\mysql'
$runtime = Get-Content -LiteralPath (Join-Path $localRoot 'runtime.json') -Raw | ConvertFrom-Json
$mysqlExe = Join-Path $runtime.installDirectory 'bin\mysql.exe'
$clientFile = Join-Path $localRoot 'app-client.ini'

if ($Query) {
    & $mysqlExe "--defaults-extra-file=$clientFile" --table "--execute=$Query"
} else {
    & $mysqlExe "--defaults-extra-file=$clientFile"
}
if ($LASTEXITCODE -ne 0) {
    throw 'MySQL client failed. Start the database with .\scripts\start-db.ps1 first.'
}
