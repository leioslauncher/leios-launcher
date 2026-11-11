# Builds the signed release APK and App Bundle with your own key.
#   .\dev-tools\build-release.ps1 -Keystore D:\keys\leios-release.jks
# -Pseudo adds Android's pseudo-languages (en-XA, ar-XB) for testing translations; never publish that build.
# Asks for the keystore password. It's kept only in this PowerShell process and never written to a file.
# JAVA_HOME and ANDROID_HOME must be set, as for any build (see the README).
param(
    [Parameter(Mandatory)][string]$Keystore,
    [string]$Alias = 'leios',
    [switch]$Pseudo
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Keystore)) { throw "No keystore at $Keystore" }
$launcher = Join-Path $PSScriptRoot '..\launcher'

$secure = Read-Host 'Keystore password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $env:LEIOS_STORE_FILE = (Resolve-Path $Keystore).Path
    $env:LEIOS_KEY_ALIAS = $Alias
    # PKCS12 keystores use one password for the store and the key.
    $env:LEIOS_STORE_PASSWORD = $password
    $env:LEIOS_KEY_PASSWORD = $password
    Push-Location $launcher
    try {
        $extra = @(); if ($Pseudo) { $extra += '-Ppseudo' }
        & .\gradlew.bat --no-daemon clean assembleRelease bundleRelease @extra
        if ($LASTEXITCODE -ne 0) { throw 'The release build failed.' }
    } finally {
        Pop-Location
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $password = $null
    Remove-Item Env:LEIOS_STORE_FILE, Env:LEIOS_STORE_PASSWORD, Env:LEIOS_KEY_ALIAS, Env:LEIOS_KEY_PASSWORD -ErrorAction SilentlyContinue
}
"Built:"
Get-ChildItem (Join-Path $launcher 'app\build\outputs') -Recurse -Include *.apk, *.aab | ForEach-Object { "  $($_.FullName)" }
