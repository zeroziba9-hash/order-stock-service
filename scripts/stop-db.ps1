$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local\mysql'
$runtime = Get-Content -LiteralPath (Join-Path $localRoot 'runtime.json') -Raw | ConvertFrom-Json
$mysqlExe = Join-Path $runtime.installDirectory 'bin\mysql.exe'
$mysqlAdminExe = Join-Path $runtime.installDirectory 'bin\mysqladmin.exe'
$rootClientFile = Join-Path $localRoot 'root-client.ini'

# Confirm the target data directory before shutting down the server.
$actualData = & $mysqlExe "--defaults-extra-file=$rootClientFile" --batch --skip-column-names --execute='SELECT @@datadir;'
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot connect to this project database. No server was stopped.'
}
$actualPath = [IO.Path]::GetFullPath(($actualData | Select-Object -Last 1).Trim()).TrimEnd('\', '/')
$expectedPath = [IO.Path]::GetFullPath($runtime.dataDirectory).TrimEnd('\', '/')
if ($actualPath -ine $expectedPath) {
    throw 'The connected server belongs to another data directory. No server was stopped.'
}
& $mysqlAdminExe "--defaults-extra-file=$rootClientFile" shutdown
if ($LASTEXITCODE -ne 0) {
    throw 'MySQL shutdown failed.'
}
Write-Output 'Project MySQL stopped. Saved data is preserved.'
