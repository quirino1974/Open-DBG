# Avvio di DBGuardian su Windows (PowerShell, con Docker Desktop in esecuzione).
# Crea il file .env la prima volta, genera APP_SECRET_KEY e avvia i container.
$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot
if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $key = [Convert]::ToBase64String($bytes)
    $righe = Get-Content ".env" | ForEach-Object { if ($_ -match '^APP_SECRET_KEY=') { "APP_SECRET_KEY=$key" } else { $_ } }
    Set-Content -Path ".env" -Value $righe -Encoding ascii
    Write-Host "Creato .env con una nuova APP_SECRET_KEY. Conservane una copia al sicuro."
}
docker compose up -d --build
Write-Host "DBGuardian su http://localhost:8080 (utente admin, password admin: cambiarla al primo accesso)"
