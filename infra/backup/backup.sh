#!/usr/bin/env bash
# Sauvegarde chiffrée PostgreSQL + configuration (CDC §11.1). Planifier via cron/systemd timer (ex. toutes les 15 min pour le WAL, quotidien pour le dump).
# Variables : PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE BACKUP_DIR BACKUP_GPG_RECIPIENT (clé publique GPG du client) RETENTION_DAYS
set -euo pipefail
: "${BACKUP_DIR:?}" "${BACKUP_GPG_RECIPIENT:?}"
PGDATABASE="${PGDATABASE:-vas}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP_DIR"
out="$BACKUP_DIR/vas-$ts.dump.gpg"

# Format custom (restauration sélective possible), chiffré pour le destinataire : la clé privée reste au client.
pg_dump --format=custom --no-owner "$PGDATABASE" | gpg --batch --yes --trust-model always --encrypt --recipient "$BACKUP_GPG_RECIPIENT" --output "$out"
sha256sum "$out" > "$out.sha256"
echo "sauvegarde OK : $out ($(du -h "$out" | cut -f1))"

# Rétention
find "$BACKUP_DIR" -name 'vas-*.dump.gpg*' -mtime +"$RETENTION_DAYS" -delete

# Copie hors site optionnelle (OFFSITE_TARGET = destination rsync/ssh ou bucket monté)
if [[ -n "${OFFSITE_TARGET:-}" ]]; then rsync -a "$out" "$out.sha256" "$OFFSITE_TARGET"/; fi
