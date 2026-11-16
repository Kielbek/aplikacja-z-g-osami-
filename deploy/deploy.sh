#!/usr/bin/env bash
# Wdrożenie / aktualizacja jednym poleceniem (uruchom na serwerze w katalogu repozytorium).
#
#   bash deploy/deploy.sh app      # serwer aplikacji
#   bash deploy/deploy.sh engine   # serwer GPU (wariant HTTPS); ENGINE_BIND w .env = wariant sieci prywatnej
set -euo pipefail
ROLE="${1:?Podaj rolę: app albo engine}"
cd "$(dirname "$0")/.."
git pull --ff-only
cd "deploy/$ROLE"
[ -f .env ] || { echo "Brak deploy/$ROLE/.env — skopiuj .env.example i uzupełnij."; exit 1; }
if [ "$ROLE" = "engine" ] && ! grep -qE '^ENGINE_BIND=.+' .env; then
  docker compose --profile https up -d --build
else
  docker compose up -d --build
fi
docker compose ps
echo "Logi: cd deploy/$ROLE && docker compose logs -f --tail=100"
