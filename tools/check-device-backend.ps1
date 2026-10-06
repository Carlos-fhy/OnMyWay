param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$health = Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 5
if ($health.status -ne 'ok') { throw 'Backend health check failed.' }
if (-not $Serial) {
    $devices = @(& $adbPath devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Connect one authorized USB device or supply -Serial.' }
    $Serial = $devices[0]
}
$packagePath = & $adbPath -s $Serial shell pm path com.onmyway.app
if ($LASTEXITCODE -ne 0 -or ($packagePath -join "`n") -notmatch 'package:.+com\.onmyway\.app') {
    throw 'OnMyWay is not installed on the selected device.'
}
& $adbPath -s $Serial reverse tcp:8000 tcp:8000
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed.' }
$response = & $adbPath -s $Serial shell "(printf 'GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n'; sleep 2) | toybox nc -w 5 127.0.0.1 8000"
if ($LASTEXITCODE -ne 0 -or ($response -join "`n") -notmatch 'HTTP/1\.[01] 200 OK' -or ($response -join "`n") -notmatch '"status"\s*:\s*"ok"') {
    throw 'Phone-to-backend health check failed.'
}
Write-Output 'PASS: OnMyWay installed, backend healthy, ADB reverse restored, phone received HTTP 200.'
