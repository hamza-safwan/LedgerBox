$ErrorActionPreference = "Stop"
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up --build -d
Write-Host "LedgerBank: http://localhost:8080"
Write-Host "Mailpit:    http://localhost:8025"
Write-Host "Grafana:    http://localhost:3001"

