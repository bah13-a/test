#!/usr/bin/env bash
# Sauvegarde chiffrée PostgreSQL + configuration (CDC §11.1). Planifier via cron/systemd timer (ex. toutes les 15 min pour le WAL, quotidien pour le dump).
# Variables (toutes lues dans le .env unique) : BACKUP_DIR BACKUP_GPG_RECIPIENT (clé publique GPG du client) BACKUP_RETENTION_DAYS OFFSITE_TARGET ; PG* déduites de DB_URL/DB_USER/DB_PASSWORD
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/../lib/env.sh"   # .env unique (variables déjà exportées prioritaires)
: "${BACKUP_DIR:?BACKUP_DIR requis (.env)}" "${BACKUP_GPG_RECIPIENT:?BACKUP_GPG_RECIPIENT requis (.env)}"
PGDATABASE="${PGDATABASE:-vas}"
keep_days="${BACKUP_RETENTION_DAYS:-30}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP_DIR"
out="$BACKUP_DIR/vas-$ts.dump.gpg"

# Format custom (restauration sélective possible), chiffré pour le destinataire : la clé privée reste au client.
pg_dump --format=custom --no-owner "$PGDATABASE" | gpg --batch --yes --trust-model always --encrypt --recipient "$BACKUP_GPG_RECIPIENT" --output "$out"
sha256sum "$out" > "$out.sha256"
echo "sauvegarde OK : $out ($(du -h "$out" | cut -f1))"

# Rétention
find "$BACKUP_DIR" -name 'vas-*.dump.gpg*' -mtime +"$keep_days" -delete

# Copie hors site optionnelle (OFFSITE_TARGET = destination rsync/ssh ou bucket monté)
if [[ -n "${OFFSITE_TARGET:-}" ]]; then rsync -a "$out" "$out.sha256" "$OFFSITE_TARGET"/; fi
