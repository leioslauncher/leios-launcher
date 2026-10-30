# Disable packages for user 0, one pm call per package, and log them for restore.ps1.
#   .\setup-kit\disable.ps1 com.jmgo.spider com.google.android.feedback
param([Parameter(Mandatory, ValueFromRemainingArguments)][string[]]$Packages)
$tv = Join-Path $PSScriptRoot 'tv.ps1'
$log = Join-Path $PSScriptRoot 'disabled-packages.txt'

# Never touch these: core Android (TV may not boot), what the projector needs to work, factory menus.
$protected = @(
    '^android$', '^com\.android\.systemui$', '^com\.android\.shell$', '^com\.android\.phone$',
    '^com\.android\.settings$', '^com\.android\.tv\.settings$',
    'permissioncontroller', 'packageinstaller', '\.ext\.services$', '\.ext\.shared$', 'modulemetadata',
    '^com\.google\.android\.overlay\.modules',
    '^com\.android\.providers\.(?!calendar$|contacts$|userdictionary$|blockednumber$)',
    '^com\.android\.vending$', '^com\.google\.android\.gms$', '^com\.google\.android\.gsf$',
    '^com\.google\.android\.inputmethod\.latin$', '^com\.google\.android\.tv\.remote\.service$',
    '^com\.android\.bluetooth', '^com\.mediatek\.tv', '^com\.mediatek\.tvinput$', '^com\.google\.android\.tv\.inputplayer$',
    '^com\.jmgo\.(hippo|ptz|autokeystone|tofautofocus|dewarping|autobrightness|autocolortemp|aieyeprotector|customizationsetupwizard|android\.tv\.globalkeyoverlay)$',
    '^com\.softwinner\.tvremoteserver$',
    'factory', 'fac\.menu', 'hkcfac', '\.at\.factory'
)
foreach ($p in $Packages) {
    if ($protected | Where-Object { $p -match $_ }) { Write-Warning "REFUSED (protected): $p"; continue }
    $out = & $tv -Cmd "pm disable-user --user 0 $p"
    "$p -> $out"
    if ($out -match 'disabled-user') {
        if (-not ((Test-Path $log) -and (Select-String -Path $log -SimpleMatch -Pattern $p -Quiet))) { Add-Content -Encoding utf8 $log $p }
    }
}
"--- now disabled on TV ---"
& $tv -Cmd 'pm list packages -d'
