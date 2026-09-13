#!/usr/bin/env sh
set -eu
[ -f .env ] || cp .env.example .env
docker compose up --build -d
printf '%s\n' 'LedgerBank: http://localhost:8080' 'Mailpit: http://localhost:8025' 'Grafana: http://localhost:3001'

