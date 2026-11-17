# Feeds sample tool calls to guard.ps1 and checks each decision. Changes nothing.
#   powershell -ExecutionPolicy Bypass -File .claude\hooks\test-guard.ps1
$guard = Join-Path $PSScriptRoot 'guard.ps1'
# A made-up private path, so the test never depends on (or prints) this PC's real ones.
$env:LEIOS_PRIVATE_PATHS = 'X:\NotReal\Keys;X:\NotReal\secret-log.txt'

$cases = @(
    # tool, input, expected
    @('PowerShell', @{ command = 'git push origin main' }, 'ask'),
    @('PowerShell', @{ command = '& $git push origin v1.4' }, 'ask'),
    @('PowerShell', @{ command = "& 'C:\tools\git.exe' -C . push --tags" }, 'ask'),
    @('Bash',       @{ command = 'gh release create v1.4 x.apk' }, 'ask'),
    @('PowerShell', @{ command = ".\setup-kit\tv.ps1 -Cmd 'pm uninstall com.example'" }, 'ask'),
    @('PowerShell', @{ command = ".\setup-kit\tv.ps1 -Cmd 'pm disable-user --user 0 com.example'" }, 'ask'),
    @('PowerShell', @{ command = ".\setup-kit\tv.ps1 -Cmd 'dpm remove-active-admin x/.A'" }, 'ask'),
    @('PowerShell', @{ command = ".\setup-kit\tv.ps1 -Cmd 'settings put system x 1'" }, 'ask'),
    @('PowerShell', @{ command = 'adb -s tv shell reboot' }, 'ask'),
    @('PowerShell', @{ command = ".\setup-kit\tv.ps1 -Cmd 'am force-stop tv.leios.launcher'" }, 'deny'),
    @('PowerShell', @{ command = 'adb shell cmd activity force-stop tv.leios.launcher' }, 'deny'),
    @('Read',       @{ file_path = 'F:\repo\launcher\tmdb.properties' }, 'deny'),
    @('PowerShell', @{ command = 'Copy-Item launcher/tmdb.properties C:\temp' }, 'deny'),
    @('PowerShell', @{ command = 'git add -f launcher\tmdb.properties' }, 'deny'),
    @('Read',       @{ file_path = 'X:/NotReal/Keys/release.jks' }, 'deny'),
    @('Grep',       @{ pattern = 'x'; path = 'x:\notreal\secret-log.txt' }, 'deny'),
    @('PowerShell', @{ command = 'Get-FileHash D:\b\leios-1.3-PRIVATE-with-key.apk' }, 'deny'),
    @('Read',       @{ file_path = 'launcher\app\build\generated\source\buildConfig\debug\tv\leios\launcher\BuildConfig.java' }, 'deny'),
    @('Read',       @{ file_path = 'launcher\tmdb.properties.example' }, 'allow'),
    @('Read',       @{ file_path = 'launcher\app\build.gradle' }, 'allow'),
    @('Write',      @{ file_path = 'CLAUDE.md'; content = 'never commit tmdb.properties' }, 'allow'),
    @('Grep',       @{ pattern = 'tmdb.properties'; path = 'launcher' }, 'allow'),
    @('PowerShell', @{ command = 'git status; git log --oneline -5' }, 'allow'),
    @('PowerShell', @{ command = 'gitleaks git --no-banner' }, 'allow'),
    @('PowerShell', @{ command = ".\setup-kit\tv.ps1 -Cmd 'settings get secure enabled_accessibility_services'" }, 'allow')
)

$fail = 0
foreach ($c in $cases) {
    $json = @{ tool_name = $c[0]; tool_input = $c[1] } | ConvertTo-Json -Compress
    $out = $json | powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $guard
    $got = if ($out) { ($out | ConvertFrom-Json).hookSpecificOutput.permissionDecision } else { 'allow' }
    $ok = $got -eq $c[2]
    if (-not $ok) { $fail++ }
    '{0}  {1,-5} (expected {2,-5})  {3}: {4}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $got, $c[2], $c[0], (($c[1].Values | Select-Object -Last 1))
}
"`n$($cases.Count - $fail) of $($cases.Count) passed."
if ($fail) { exit 1 }
