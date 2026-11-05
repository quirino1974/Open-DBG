#!/usr/bin/env sh
# Avvio di DBGuardian su Linux e macOS.
# Crea il file .env la prima volta, genera APP_SECRET_KEY e avvia i container.
set -e
cd "$(dirname "$0")"
if [ ! -f .env ]; then
  cp .env.example .env
  KEY=$(openssl rand -base64 32 2>/dev/null || python3 -c 'import os,base64;print(base64.b64encode(os.urandom(32)).decode())')
  sed -i.bak "s|^APP_SECRET_KEY=.*|APP_SECRET_KEY=$KEY|" .env && rm -f .env.bak
  echo "Creato .env con una nuova APP_SECRET_KEY. Conservane una copia al sicuro."
fi
docker compose up -d --build
echo "DBGuardian su http://localhost:8080 (utente admin, password admin: cambiarla al primo accesso)"
