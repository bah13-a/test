#!/usr/bin/env bash
# Fichiers de données de test (plages, portabilité, catalogue) lus par l'application via ROUTING_RANGES_FILE,
# ROUTING_PORTED_FILE et CATALOG_FILE ; vérifiés par full-stack.mjs. Usage : it-data.sh <répertoire>
set -euo pipefail
DIR="${1:?répertoire}"
mkdir -p "$DIR"
printf 'prefix;operator\n# plage de test chargée par ROUTING_RANGES_FILE\n7777;ORANGE\n' > "$DIR/ranges.csv"
printf 'msisdn;operator\n77771234;OOREDOO\n' > "$DIR/ported.csv"
cat > "$DIR/catalog.json" <<'JSON'
{"partners":[{"name":"Partenaire Catalogue","sharePercent":20}],
 "services":[{"name":"Service Catalogue","type":"VOTE","operator":"TT","shortCode":"85503","partner":"Partenaire Catalogue","keywords":["CAT"],
   "tariffs":[{"eventType":"MT","grossAmount":0.5,"operatorPercent":40,"taxPercent":19}]}]}
JSON
