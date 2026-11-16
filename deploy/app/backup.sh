#!/usr/bin/env bash
# Kopia zapasowa bazy Narrivy (konta, projekty, rozliczenia). Nagrania są w R2 — mają własną trwałość.
#
#   bash deploy/app/backup.sh                       # ręcznie
#   crontab: 15 3 * * * /root/aplikacja/deploy/app/backup.sh >> /var/log/narrivy-backup.log 2>&1
#
# Trzyma 14 ostatnich kopii w /var/backups/narrivy. Zalecane: kopiuj je też poza serwer (np. do osobnego bucketa R2).
set -euo pipefail
cd "$(dirname "$0")"
DIR=/var/backups/narrivy
mkdir -p "$DIR"
FILE="$DIR/narrivy-$(date +%Y%m%d-%H%M).sql.gz"
docker compose exec -T postgres pg_dump -U voicecraft --clean --if-exists voicecraft | gzip > "$FILE"
chmod 600 "$FILE"
ls -1t "$DIR"/narrivy-*.sql.gz | tail -n +15 | xargs -r rm --
echo "$(date -Is) kopia: $FILE ($(du -h "$FILE" | cut -f1))"
