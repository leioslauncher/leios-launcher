# Claude Code PreToolUse guard for this repo (wired up in .claude/settings.json).
#   Blocks: force-stop (am force-stop, cmd activity force-stop), and any tool call that touches a
#           private file: signing keys, keystore.properties, tmdb.properties, PRIVATE builds, the
#           generated BuildConfig (it holds the TMDB key), and this PC's own private paths.
#   Asks the owner first: git push (branches or tags), gh release, pm uninstall, pm disable(-user),
#           dpm, settings put, reboot.
# This PC's private paths are in LEIOS_PRIVATE_PATHS (separated by ;) in .claude/settings.local.json,
# which is never committed. Keep this file free of personal details.
# Test it with: powershell -ExecutionPolicy Bypass -File .claude\hooks\test-guard.ps1
$ErrorActionPreference = 'Stop'

function Out-Decision([string]$Decision, [string]$Reason) {
    $o = @{ hookSpecificOutput = @{ hookEventName = 'PreToolUse'; permissionDecision = $Decision; permissionDecisionReason = $Reason } }
    [Console]::Out.Write(($o | ConvertTo-Json -Compress -Depth 4))
    exit 0
}

try {
    $reader = New-Object IO.StreamReader([Console]::OpenStandardInput(), [Text.Encoding]::UTF8)
    $call = $reader.ReadToEnd() | ConvertFrom-Json
} catch {
    Out-Decision 'deny' "The guard hook couldn't read this tool call, so it was blocked to be safe: $($_.Exception.Message)"
}

$tool = [string]$call.tool_name
$in = $call.tool_input
$isShell = ($tool -eq 'Bash') -or ($tool -eq 'PowerShell')
if ($isShell) {
    $texts = @([string]$in.command)
} else {
    # File tools: only the paths they act on, never file contents or search patterns.
    $texts = @($in.file_path, $in.path, $in.notebook_path, $in.glob)
}
$texts = @($texts | Where-Object { $_ } | ForEach-Object { [string]$_ })
if ($texts.Count -eq 0) { exit 0 }

# 1. Private files.
$privatePatterns = @(
    '\.(jks|keystore|p12)\b',                  # signing keys
    'keystore\.properties(?!\.example)',
    'tmdb\.properties(?!\.example)',           # the TMDB API key
    'PRIVATE[^\\/\s''"]*\.(apk|aab)\b',        # builds with a key inside
    'generated[\\/]+source[\\/]+buildConfig'   # BuildConfig.java holds the TMDB key
)
$localList = $env:LEIOS_PRIVATE_PATHS
if (-not $localList) {
    $settings = Join-Path $PSScriptRoot '..\settings.local.json'
    if (Test-Path $settings) {
        try { $localList = (Get-Content $settings -Raw | ConvertFrom-Json).env.LEIOS_PRIVATE_PATHS } catch { }
    }
}
function Normalize([string]$s) { (($s -replace '/', '\') -replace '\\+', '\').ToLowerInvariant() }
$localPaths = @("$localList" -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ } |
    ForEach-Object { (Normalize $_).TrimEnd('\') })

$privateReason = 'Blocked: this touches a private file (signing keys, the TMDB key, a PRIVATE build, ' +
    'or a path listed in LEIOS_PRIVATE_PATHS). Claude may not read, copy or commit these. ' +
    'Ask the owner to do this step themselves.'
foreach ($t in $texts) {
    $n = Normalize $t
    foreach ($p in $privatePatterns) { if (($t -match $p) -or ($n -match $p)) { Out-Decision 'deny' $privateReason } }
    foreach ($p in $localPaths) { if ($n.Contains($p)) { Out-Decision 'deny' $privateReason } }
}

if (-not $isShell) { exit 0 }
$cmd = $texts[0]

# 2. Never force-stop: Android then switches off the launcher's accessibility service.
if ($cmd -match '\b(am|cmd\s+activity)\s+force-stop\b') {
    Out-Decision 'deny' ('Blocked: force-stop is never allowed in this project. Android switches off the ' +
        'launcher''s accessibility service (the quick-settings panel) when its app is force-stopped.')
}

# 3. Ask the owner first.
$askRules = [ordered]@{
    'git push'                  = '\bgit(\.exe)?[''"]?\s+(-[cC]\s+\S+\s+|--?[\w-]+(=\S+)?\s+)*push\b'
    'gh release'                = '\bgh(\.exe)?[''"]?\s+release\b'
    'pm uninstall'              = '\bpm\s+uninstall\b'
    'pm disable / disable-user' = '\bpm\s+disable(-user)?\b'
    'dpm'                       = '\bdpm\b'
    'settings put'              = '\bsettings\s+put\b'
    'reboot'                    = '\breboot\b'
}
$hits = @(foreach ($k in $askRules.Keys) { if ($cmd -match $askRules[$k]) { $k } })
if ($hits.Count -gt 0) {
    Out-Decision 'ask' ("This needs the owner's OK in this project: " + ($hits -join ', ') + '.')
}
exit 0
