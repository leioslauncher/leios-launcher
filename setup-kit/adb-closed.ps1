# Check that network ADB is closed on the TV: only "connection refused" proves it.
#   .\setup-kit\adb-closed.ps1 192.0.2.10
param([string]$TvHost = $env:TV_HOST, [int]$Port = 5555)
if (-not $TvHost) { throw 'Set $env:TV_HOST to the TV''s IP address first, or pass it as the first argument.' }
$client = New-Object Net.Sockets.TcpClient
try {
    $client.Connect($TvHost, $Port)
    Write-Host "OPEN: ${TvHost}:$Port accepts connections - network ADB is still on." -ForegroundColor Red
    exit 1
} catch [Net.Sockets.SocketException] {
    if ($_.Exception.SocketErrorCode -eq 'ConnectionRefused') {
        Write-Host "CLOSED: ${TvHost}:$Port refused the connection." -ForegroundColor Green
        exit 0
    }
    Write-Host "UNKNOWN: $($_.Exception.SocketErrorCode) - TV off or unreachable proves nothing; turn it on and retry." -ForegroundColor Yellow
    exit 2
} finally {
    $client.Close()
}
