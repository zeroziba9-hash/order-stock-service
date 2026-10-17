[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status')][string]$Action = 'start',
    [string]$Config = '.local/launcher.json',
    [switch]$Build,
    [switch]$Initialize,
    [switch]$NoBrowser,
    [ValidateRange(30, 600)][int]$TimeoutSeconds = 240
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
function Resolve-LocalPath([string]$Value) {
    if ([IO.Path]::IsPathRooted($Value)) { return [IO.Path]::GetFullPath($Value) }
    return [IO.Path]::GetFullPath((Join-Path $projectRoot $Value))
}
function Invoke-Docker([string[]]$DockerArguments, [switch]$AllowFailure) {
    # Never forward Compose config, environment values or raw diagnostics to the console.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & $script:dockerCommand @DockerArguments 2>&1; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0 -and -not $AllowFailure) { throw 'Docker operation failed. Check Docker Desktop and private local configuration.' }
    return @{ Code = $code; Text = ($output | ForEach-Object { "$_" }) -join "`n" }
}
function Test-Ready([string]$Origin) {
    try {
        $response = Invoke-RestMethod -Uri ($Origin + '/actuator/health/readiness') -TimeoutSec 5
        return $response.status -eq 'UP'
    } catch { return $false }
}
try {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { throw 'Docker CLI is missing. Install Docker Desktop first.' }
    $script:dockerCommand = $docker.Source
    $configPath = Resolve-LocalPath $Config
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'Launcher config is missing. Copy scripts/launcher.example.json to .local/launcher.json and select the existing project and environment file.'
    }
    $settings = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($settings.version -ne 1 -or $settings.project -notmatch '^order-stock-[a-z0-9][a-z0-9-]{0,62}$') {
        throw 'Use launcher version 1 and an explicit order-stock-* Compose project name.'
    }
    $envPath = Resolve-LocalPath $settings.envFile
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) { throw 'The selected private environment file is missing. Existing files are never generated or overwritten.' }
    $composeFiles = @($settings.composeFiles)
    if ($composeFiles.Count -eq 0) { throw 'Select at least one Compose file.' }
    $compose = @('compose', '-p', $settings.project, '--env-file', $envPath)
    foreach ($file in $composeFiles) {
        $path = Resolve-LocalPath $file
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'A selected Compose file is missing.' }
        $compose += @('-f', $path)
    }
    $contextHost = (Invoke-Docker -DockerArguments @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}')).Text.Trim()
    if (-not ($contextHost.StartsWith('npipe://') -or $contextHost.StartsWith('unix://'))) { throw 'This launcher supports a local Docker context only.' }
    $engine = Invoke-Docker -DockerArguments @('info', '--format', '{{.ServerVersion}}') -AllowFailure
    if ($engine.Code -ne 0 -and $Action -eq 'start') {
        $desktopPaths = @(
            (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/Docker Desktop.exe'),
            (Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe')
        )
        $desktop = $desktopPaths | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if (-not $desktop) { throw 'Docker engine is stopped. Start Docker Desktop, then retry.' }
        Write-Output 'Starting Docker Desktop...'
        Start-Process -FilePath $desktop -WindowStyle Hidden
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            Start-Sleep -Seconds 3
            $engine = Invoke-Docker -DockerArguments @('info', '--format', '{{.ServerVersion}}') -AllowFailure
        } while ($engine.Code -ne 0 -and (Get-Date) -lt $deadline)
    }
    if ($engine.Code -ne 0) { throw 'Docker engine is not ready.' }
    $rendered = (Invoke-Docker ($compose + @('config', '--format', 'json'))).Text | ConvertFrom-Json
    if ($rendered.services.app.environment.SPRING_PROFILES_ACTIVE -ne 'local') { throw 'Only the local application profile is supported.' }
    $ports = @($rendered.services.app.ports | Where-Object { $_.target -eq 8080 })
    if ($ports.Count -ne 1 -or $ports[0].host_ip -ne '127.0.0.1' -or $rendered.services.db.ports) {
        throw 'App port must bind to 127.0.0.1; the database must stay internal.'
    }
    $origin = 'http://localhost:' + $ports[0].published
    $dataMount = @($rendered.services.db.volumes | Where-Object { $_.type -eq 'volume' -and $_.target -eq '/var/lib/mysql' })
    if ($dataMount.Count -ne 1) { throw 'Select one named database volume.' }
    $volume = $rendered.volumes.PSObject.Properties[$dataMount[0].source].Value.name
    if (-not $volume) { throw 'Database volume identity is missing.' }
    $services = @('app', 'db')
    if ($rendered.services.PSObject.Properties['mailpit']) { $services += 'mailpit' }
    if ($Action -eq 'stop') {
        $null = Invoke-Docker ($compose + @('stop', '--timeout', '30') + $services)
        Write-Output ('Stopped ' + $settings.project + '. Containers and database volume retained.')
        exit 0
    }
    if ($Action -eq 'status') {
        $rows = (Invoke-Docker ($compose + @('ps', '--all', '--format', '{{.Service}}: {{.State}} ({{.Health}})'))).Text
        Write-Output ('Project: ' + $settings.project)
        Write-Output $rows
        Write-Output ('URL: ' + $origin + '/#shop')
        $appId = (Invoke-Docker ($compose + @('ps', '-q', 'app'))).Text.Trim()
        $running = $appId -and (Invoke-Docker -DockerArguments @('inspect', $appId, '--format', '{{.State.Running}}')).Text.Trim() -eq 'true'
        if ($running -and (Test-Ready $origin)) { Write-Output 'Readiness: UP'; exit 0 }
        Write-Output 'Readiness: unavailable'; exit 1
    }
    $exists = Invoke-Docker -DockerArguments @('volume', 'inspect', $volume, '--format', '{{.Name}}') -AllowFailure
    if ($exists.Code -ne 0 -and -not $Initialize) {
        throw 'The selected database volume does not exist. Check the project name. Only a deliberate new installation may use -Initialize.'
    }
    Write-Output ('Starting ' + $settings.project + '... Existing database data is retained.')
    $buildOption = if ($Build) { '--build' } else { '--no-build' }
    $null = Invoke-Docker ($compose + @('up', $buildOption, '--wait', '--wait-timeout', "$TimeoutSeconds") + $services)
    if (-not (Test-Ready $origin)) { throw 'Containers started, but application readiness is unavailable. Check status before using the app.' }
    Write-Output ('Ready: ' + $origin + '/#shop')
    if (-not $NoBrowser) { Start-Process ($origin + '/#shop') -WindowStyle Hidden }
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
