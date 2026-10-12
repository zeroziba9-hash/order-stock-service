$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$appJar = Join-Path $projectRoot 'build\libs\order-stock-service-0.0.1-SNAPSHOT.jar'
$classPathRoot = Join-Path $projectRoot 'build\classes\java\main'
$listeners = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -eq 0) {
    Write-Output 'No application is listening on port 8080.'
    return
}

foreach ($processId in ($listeners.OwningProcess | Select-Object -Unique)) {
    $appProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
    $commandLine = $appProcess.CommandLine
    $isProjectJar = $commandLine -and $commandLine.Contains($appJar)
    $isProjectBootRun = $commandLine -and $commandLine.Contains($classPathRoot) -and
        $commandLine.Contains('com.zeroziba.orderstock.OrderStockServiceApplication')
    if ($appProcess.Name -ne 'java.exe' -or -not ($isProjectJar -or $isProjectBootRun)) {
        throw 'Port 8080 belongs to another application. It was left unchanged.'
    }
    Stop-Process -Id $processId
    Wait-Process -Id $processId -Timeout 20 -ErrorAction SilentlyContinue
}
Write-Output 'Stopped this project application on port 8080. MySQL remains running.'
