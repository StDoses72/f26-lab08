$ErrorActionPreference = 'Stop'

try {
    $payload = [Console]::In.ReadToEnd()
    $env:PYTHONUTF8 = '1'
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $payload | & python (Join-Path $PSScriptRoot 'protect.py')
    if ($LASTEXITCODE -ne 0) {
        exit 2
    }
} catch {
    [Console]::Error.WriteLine("protect hook failed: $($_.Exception.Message)")
    exit 2
}

exit 0
