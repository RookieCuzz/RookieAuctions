param(
    [switch]$Reset,
    [switch]$FullMatrix
)

$ErrorActionPreference = 'Stop'
$integrationRoot = (Resolve-Path (Join-Path $PSScriptRoot '.')).Path
$serverRoot = Join-Path $integrationRoot 'server'
$logPath = Join-Path $serverRoot 'logs/latest.log'

if ($FullMatrix) { $Reset = $true }
if ($Reset -or -not (Test-Path -LiteralPath (Join-Path $serverRoot 'plugins/RookieAuctions.jar'))) {
    & (Join-Path $PSScriptRoot 'prepare-test-server.ps1') -Reset:$Reset -FullMatrix:$FullMatrix
}

if (-not (Test-Path -LiteralPath (Join-Path $integrationRoot 'node_modules/mineflayer'))) {
    Write-Host 'Installing Mineflayer ...'
    Push-Location $integrationRoot
    try { & npm.cmd install --omit=dev } finally { Pop-Location }
}

if (netstat -ano | Select-String ':25565 .*LISTENING') {
    throw 'Port 25565 is already in use; stop the existing server before running the smoke test.'
}
if (Test-Path -LiteralPath $logPath) {
    Remove-Item -LiteralPath $logPath -Force -ErrorAction SilentlyContinue
}

Write-Host 'Starting Paper test server ...'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
if ($java -ne 'java' -and -not (Test-Path -LiteralPath $java)) { $java = 'java' }
$server = Start-Process -FilePath $java -ArgumentList @('-Xms1G', '-Xmx1G', '-jar', 'paper-1.21.4-232.jar', '--nogui') `
    -WorkingDirectory $serverRoot -PassThru -WindowStyle Hidden

function Stop-PaperTree {
    param([int]$RootId)
    $tree = [System.Collections.Generic.HashSet[int]]::new()
    [void]$tree.Add($RootId)
    for ($pass = 0; $pass -lt 6; $pass++) {
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $tree.Contains([int]$_.ParentProcessId) } |
            ForEach-Object { [void]$tree.Add([int]$_.ProcessId) }
    }
    # `java` on Windows can be a short-lived javapath launcher which leaves the
    # real JVM detached from the Start-Process PID.  Match only this test jar so
    # cleanup remains scoped to the integration server.
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*paper-1.21.4-232.jar*' } |
        ForEach-Object { [void]$tree.Add([int]$_.ProcessId) }
    foreach ($processId in $tree) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }
}

try {
    $deadline = (Get-Date).AddSeconds(180)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $logPath) {
            if (Select-String -LiteralPath $logPath -Pattern 'Done \(' -ErrorAction SilentlyContinue) {
                $ready = $true
                break
            }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Paper did not reach the Done state within 180 seconds' }

    $env:MC_HOST = '127.0.0.1'
    $env:MC_PORT = '25565'
    $env:BOT_USERNAME = 'TestAdmin'
    Push-Location $integrationRoot
    $botScript = if ($FullMatrix) { 'full-matrix-test.js' } else { 'bot-smoke-test.js' }
    try { & node $botScript; if ($LASTEXITCODE -ne 0) { throw "Mineflayer test failed with exit code $LASTEXITCODE" } }
    finally { Pop-Location }
    Write-Host 'Mineflayer smoke completed successfully.'
}
finally {
    Stop-PaperTree -RootId $server.Id
}
