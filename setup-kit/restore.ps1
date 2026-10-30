# Re-enable everything disabled-packages.txt lists, put DNS back, and hand Home back to Google TV.
#   .\setup-kit\restore.ps1              re-enable all
#   .\setup-kit\restore.ps1 com.tubitv   re-enable just one
# Needs network ADB on the TV (Developer options > USB debugging on).
param([string[]]$Only)
$tv = Join-Path $PSScriptRoot 'tv.ps1'
$list = if ($Only) { $Only } else { Get-Content (Join-Path $PSScriptRoot 'disabled-packages.txt') | Where-Object { $_ -and $_ -notmatch '^#' } }
foreach ($p in $list) { "$p -> $(& $tv -Cmd "pm enable --user 0 $p")" }
if (-not $Only) {
    & $tv -Cmd 'settings put global private_dns_mode opportunistic'
    # Brings back JMGO's dark-scene laser dimming (black-current policy); applies after the reboot.
    & $tv -Cmd 'settings delete system black_current_enable'
    & $tv -Cmd 'cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity'
    "Done. Reboot the TV: .\setup-kit\tv.ps1 -Cmd reboot"
}
