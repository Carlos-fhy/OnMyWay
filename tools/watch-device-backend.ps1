param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$Minutes = 120
)
# Development-session helper only; exits after the specified time.
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$deadline = (Get-Date).AddMinutes($Minutes)
$lastState = ''
while ((Get-Date) -lt $deadline) {
    $state = (& $adbPath -s $Serial get-state 2>$null) -join ''
    if ($state -eq 'device') {
        $rules = (& $adbPath -s $Serial reverse --list 2>$null) -join "`n"
        if ($rules -notmatch 'tcp:8000\s+tcp:8000') {
            & $adbPath -s $Serial reverse tcp:8000 tcp:8000 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                Write-Output "$(Get-Date -Format o) USB reverse restored."
            }
        }
    }
    if ($state -ne $lastState) {
        Write-Output "$(Get-Date -Format o) Device state: $state"
        $lastState = $state
    }
    Start-Sleep -Seconds 3
}
