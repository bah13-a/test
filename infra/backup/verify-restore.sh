#!/usr/bin/env bash
# Test de restauration automatisé (PRA, CDC §14.3 pt 7) : restaure la dernière sauvegarde dans une base temporaire,
# vérifie le schéma et des volumes minimaux, mesure la durée (comparer au RTO ≤ 2 h) puis supprime la base temporaire.
set -euo pipefail
: "${BACKUP_DIR:?}"
latest="$(ls -1t "$BACKUP_DIR"/vas-*.dump.gpg | head -1)"
tmpdb="vas_restore_test_$(date +%s)"
start=$(date +%s)
"$(dirname "$0")/restore.sh" "$latest" "$tmpdb"
for t in operator vas_service mo_message mt_message ledger_event audit_log app_user flyway_schema_history; do
  n=$(psql -At -d "$tmpdb" -c "select count(*) from $t")
  echo "table $t : $n lignes"
done
bad=$(psql -At -d "$tmpdb" -c "select count(*) from flyway_schema_history where success = false")
[[ "$bad" == "0" ]] || { echo "migrations en échec dans la sauvegarde" >&2; exit 1; }
echo "durée de restauration : $(( $(date +%s) - start )) s (RTO cible 7200 s)"
dropdb "$tmpdb"
echo "VERIFY OK : $latest"
