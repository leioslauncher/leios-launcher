# Gathers what a push would publish, for the privacy-reviewer agent to read.
# Read-only for the repo: it writes only to logs\push-check\<time>\ (git-ignored) and prints that folder.
#   powershell -ExecutionPolicy Bypass -File .claude\scripts\push-check.ps1
#   ... -Range origin/main..HEAD                  what `git push origin main` sends (the default)
#   ... -Files CLAUDE.md,.claude\agents\x.md      also check files that aren't committed yet
# Uses $env:LEIOS_GIT and $env:LEIOS_GITLEAKS when git or gitleaks aren't on PATH.
param([string]$Range = 'origin/main..HEAD', [string[]]$Files = @())
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$git = if ($env:LEIOS_GIT) { $env:LEIOS_GIT } else { 'git' }
$leaks = if ($env:LEIOS_GITLEAKS) { $env:LEIOS_GITLEAKS } elseif (Get-Command gitleaks -ErrorAction SilentlyContinue) { 'gitleaks' } else { $null }
$images = '\.(jpe?g|png|gif|webp|tiff?|heic)$'
# `-File` passes "a,b,c" as one string; accept both forms.
$Files = @($Files | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
# gitleaks runs git itself, so git must be on PATH for it.
if ($env:LEIOS_GIT) { $env:PATH = (Split-Path $env:LEIOS_GIT) + ';' + $env:PATH }
Set-Location $root

$out = Join-Path $root ('logs\push-check\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $out | Out-Null
& $git check-ignore -q (Join-Path $out 'probe.txt')
if ($LASTEXITCODE -ne 0) { throw "$out is not git-ignored; stopping so nothing private lands in the repo." }
function Save([string]$name, $lines) { $lines | Set-Content -Encoding utf8 (Join-Path $out $name) }

# Commits, with author and committer identity and time zone.
Save 'commits.txt' (& $git log --format='%H%x09author: %an <%ae> %ai%x09committer: %cn <%ce> %ci%x09%s' $Range)
Save 'messages.txt' (& $git log --format='=== %H%n%B' $Range)
Save 'files.txt' (& $git diff --name-status $Range)
Save 'diff.patch' (& $git diff --no-color $Range)

# Tags that exist only here: `git push --tags` would publish them.
$remote = @(& $git ls-remote --tags origin | ForEach-Object { (($_ -split '\s+')[1] -replace '^refs/tags/', '') -replace '\^\{\}$', '' })
Save 'tags-local-only.txt' (& $git tag -l --format='%(refname:short)%09%(taggername) %(taggeremail) %(taggerdate:iso)%09%(subject)' |
    Where-Object { ($_ -split "`t")[0] -notin $remote })

# Metadata in images: EXIF (and GPS), XMP, IPTC, comments, PNG text chunks.
function Get-ImageMeta([string]$path) {
    $b = [IO.File]::ReadAllBytes($path)
    $found = New-Object Collections.Generic.List[string]
    if ($b.Length -gt 3 -and $b[0] -eq 0xFF -and $b[1] -eq 0xD8) {
        $i = 2
        while ($i + 4 -le $b.Length -and $b[$i] -eq 0xFF) {
            $m = $b[$i + 1]
            if ($m -eq 0xDA -or $m -eq 0xD9) { break }
            if (($m -ge 0xD0 -and $m -le 0xD7) -or $m -eq 0x01) { $i += 2; continue }
            $len = $b[$i + 2] * 256 + $b[$i + 3]
            $tag = [Text.Encoding]::ASCII.GetString($b, $i + 4, [Math]::Min(40, [Math]::Max(0, $len - 2)))
            if ($m -eq 0xE1 -and $tag.StartsWith('Exif')) { $found.Add('EXIF') }
            elseif ($m -eq 0xE1 -and $tag -match 'adobe\.com/xap') { $found.Add('XMP') }
            elseif ($m -eq 0xED) { $found.Add('IPTC/Photoshop') }
            elseif ($m -eq 0xFE) { $found.Add('comment') }
            elseif ($m -ge 0xE3 -and $m -le 0xEF) { $found.Add(('APP{0}' -f ($m - 0xE0))) }
            $i += 2 + $len
        }
    } elseif ($b.Length -gt 8 -and $b[0] -eq 0x89 -and $b[1] -eq 0x50) {
        $i = 8
        while ($i + 8 -le $b.Length) {
            $len = [int]$b[$i] * 16777216 + $b[$i + 1] * 65536 + $b[$i + 2] * 256 + $b[$i + 3]
            $type = [Text.Encoding]::ASCII.GetString($b, $i + 4, 4)
            if ($type -in 'tEXt', 'iTXt', 'zTXt') {
                $key = [Text.Encoding]::ASCII.GetString($b, $i + 8, [Math]::Min(40, $len)).Split([char]0)[0]
                $found.Add("$type($key)")
            } elseif ($type -in 'eXIf', 'tIME') { $found.Add($type) }
            if ($type -eq 'IEND') { break }
            $i += 12 + $len
        }
    }
    if ($found.Contains('EXIF') -or $found.Contains('eXIf')) {
        try {
            Add-Type -AssemblyName System.Drawing
            $img = [Drawing.Image]::FromFile($path)
            $ids = $img.PropertyIdList; $img.Dispose()
            $gps = @($ids | Where-Object { $_ -le 0x1F -or $_ -eq 0x8825 }).Count -gt 0
            $found.Add("tags=$($ids.Count) gps=$(if ($gps) { 'YES' } else { 'no' })")
        } catch { }
    }
    if ($found.Count -eq 0) { 'clean' } else { $found -join ', ' }
}
$changed = @(& $git diff --name-only --diff-filter=AMR $Range) + $Files
$imageReport = foreach ($f in ($changed | Where-Object { $_ -match $images } | Sort-Object -Unique)) {
    if (Test-Path $f) { "${f}: $(Get-ImageMeta (Resolve-Path $f).Path)" } else { "${f}: not in the working tree (deleted or renamed since)" }
}
Save 'images.txt' $(if ($imageReport) { $imageReport } else { 'no images' })

# Secrets, with gitleaks (values redacted in its report).
function Run-Leaks([string[]]$argv, [string]$name) {
    # Leaks exit with 3, so a gitleaks error (exit 1) can't pass for a leak or a clean result.
    $p = Start-Process -FilePath $leaks -ArgumentList ($argv + '--exit-code=3') -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput (Join-Path $out "$name.out.txt") -RedirectStandardError (Join-Path $out "$name.txt")
    switch ($p.ExitCode) { 0 { 'no leaks' } 3 { "LEAKS FOUND (see $name.txt)" } default { "ERROR (exit $($p.ExitCode), see $name.txt)" } }
}
$leakSummary = @()
if ($leaks) {
    $leakSummary += "gitleaks commits ${Range}: " + (Run-Leaks @('git', '--no-banner', '--redact', "--log-opts=$Range", '.') 'gitleaks-commits')
    foreach ($f in $Files) {
        $safe = ($f -replace '[\\/:]', '_')
        $leakSummary += "gitleaks ${f}: " + (Run-Leaks @('dir', '--no-banner', '--redact', $f) "gitleaks-file-$safe")
    }
} else { $leakSummary += 'gitleaks: not installed (set LEIOS_GITLEAKS)' }

Save 'summary.txt' (@(
    "Range: $Range",
    "Commits: $(@(Get-Content (Join-Path $out 'commits.txt') | Where-Object { $_ }).Count)",
    "Changed files: $(@(Get-Content (Join-Path $out 'files.txt') | Where-Object { $_ }).Count)",
    "Extra files: $($Files -join ', ')",
    "Local-only tags: $(@(Get-Content (Join-Path $out 'tags-local-only.txt') | Where-Object { $_ }).Count)"
) + $leakSummary)
$out
