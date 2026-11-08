# Backward-compatible filename; current release verifies the CMD + bundled-runtime launcher.
& (Join-Path $PSScriptRoot 'verify-local-windows.ps1') @args
exit $LASTEXITCODE
