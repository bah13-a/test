#!/usr/bin/env bash
# Rotation de DATA_KEY sur la pile réelle (PostgreSQL) : arrêt de l'application, simulation, réécriture, reprise, redémarrage avec la nouvelle clé,
# puis lecture réelle des numéros par l'application. Prérequis : pile démarrée par `local-stack.sh up|test` avec la clé par défaut.
set -uo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"; JAR="$HERE/target/vas-platform-1.0.0.jar"
OLD="${OLD_DATA_KEY:-Zx9-data-key-integration-0123456789abcdefghijklmnop}"; NEW="Zx9-NOUVELLE-cle-rotation-0123456789abcdefghijklm"
export DB_URL=jdbc:postgresql://localhost:5433/vas DB_USER=vas DB_PASSWORD=Zx9-db-real-password
fail=0; ok() { echo "OK    $1"; }; ko() { echo "ECHEC $1"; fail=1; }
rekey() { OLD_DATA_KEY="$OLD" NEW_DATA_KEY="$NEW" java -Dloader.main=tn.vas.tools.Rekey -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@" 2>&1 | grep -v "^Picked up"; }
before=$(psql -h /tmp -p 5433 -U postgres -d vas -Atc "select count(*) from mt_message where msisdn like 'enc:v1:%'")
bash "$HERE/tests/integration/local-stack.sh" stop-app
out=$(rekey); echo "$out" | tail -3
echo "$out" | grep -q "échecs=0" && ok "simulation : aucune valeur illisible" || ko "simulation : valeurs illisibles"
same=$(psql -h /tmp -p 5433 -U postgres -d vas -Atc "select count(*) from mt_message where msisdn like 'enc:v1:%'")
[ "$same" = "$before" ] && ok "simulation : aucune écriture" || ko "simulation a écrit"
sample=$(psql -h /tmp -p 5433 -U postgres -d vas -Atc "select msisdn from mt_message order by id limit 1")
out=$(rekey --apply); echo "$out" | tail -2
echo "$out" | grep -q "échecs=0" && ok "réécriture appliquée sans échec" || ko "réécriture en échec"
after=$(psql -h /tmp -p 5433 -U postgres -d vas -Atc "select msisdn from mt_message order by id limit 1")
[ "$sample" != "$after" ] && ok "les valeurs stockées ont changé (nouvelle clé)" || ko "valeurs inchangées"
out=$(rekey); echo "$out" | tail -1 | grep -q "réécrites=0" && ok "reprise : plus rien à réécrire" || ko "reprise : $out"
DATA_KEY="$NEW" bash "$HERE/tests/integration/local-stack.sh" start-app >/dev/null
node "$HERE/tests/integration/rekey-check.mjs" && ok "l'application lit les numéros avec la nouvelle clé" || ko "l'application ne lit plus les numéros"
exit $fail
