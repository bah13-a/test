#!/usr/bin/env bash
# Exercice de bascule HA en PRÉPRODUCTION (étape 3 du passage en production ; CDC §12.2, NOC : semestriel).
# Pendant qu'une sonde envoie un MT toutes les 200 ms par le répartiteur, on arrête tour à tour une instance applicative,
# le primaire PostgreSQL, un nœud RabbitMQ et le primaire Redis ; on mesure l'interruption et on vérifie qu'aucun MT accepté n'est perdu.
#   BASE=http://localhost:8080 API_KEY=... SIM=98xxxxxx SENDER=85500 OPERATOR=TT tests/ha/drill.sh
# Prérequis : pile HA démarrée (docker compose -f docker-compose.yml -f infra/ha/docker-compose.ha.yml up -d), liaison SMPP vers le
# simulateur ou l'environnement de test de l'opérateur (jamais vers de vrais abonnés). Résultat : ha-drill-<date>.md
set -uo pipefail
cd "$(dirname "$0")/../.."
BASE="${BASE:-http://localhost:8080}"; : "${API_KEY:?}"; : "${SIM:?}"; SENDER="${SENDER:-85500}"; OPERATOR="${OPERATOR:-TT}"
DC="docker compose -f docker-compose.yml -f infra/ha/docker-compose.ha.yml"
RUN="$(date +%Y%m%d%H%M)"; LOG="$(mktemp)"; OUT="ha-drill-$RUN.md"; MAX_OUTAGE="${MAX_OUTAGE:-30}"

probe() {   # une ligne par tentative : horodatage(ms) code_http id
  local i=0
  while :; do
    i=$((i + 1))
    r=$(curl -s -m 5 -o - -w ' %{http_code}' -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
      -d "{\"to\":\"$SIM\",\"text\":\"HA $RUN $i\",\"operator\":\"$OPERATOR\",\"sender\":\"$SENDER\",\"clientRef\":\"ha-$RUN-$i\"}" "$BASE/api/v1/messages")
    code="${r##* }"; id=$(printf '%s' "$r" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
    echo "$(date +%s%3N) $code ${id:--}" >> "$LOG"
    sleep 0.2
  done
}

outage() {   # plus longue suite d'échecs (en secondes) depuis l'horodatage $1
  awk -v from="$1" '$1 >= from { if ($2 != 202 && $2 != 200) { if (!s) s = $1; e = $1 } else if (s) { d = e - s; if (d > m) m = d; s = 0 } }
    END { if (s) { d = e - s; if (d > m) m = d }; printf "%.1f", m / 1000 }' "$LOG"
}

primary_pg() {
  for n in pg1 pg2; do
    [ "$($DC exec -T "$n" curl -s -o /dev/null -w '%{http_code}' localhost:8008/primary 2>/dev/null)" = 200 ] && { echo "$n"; return; }
  done
}

declare -a ROWS
scenario() {   # libellé, commande d'arrêt, commande de reprise
  local label="$1" stop="$2" start="$3" t0
  t0=$(date +%s%3N)
  echo "== $label"
  eval "$stop"; sleep 45; eval "$start"; sleep 30
  local o; o=$(outage "$t0")
  ROWS+=("| $label | $o s | $(awk -v m="$MAX_OUTAGE" -v o="$o" 'BEGIN { print (o <= m) ? "OK" : "ÉCHEC" }') |")
}

probe & PROBE=$!
trap 'kill $PROBE 2>/dev/null; rm -f "$LOG"' EXIT
sleep 20
APP1=$($DC ps -q app | head -1)
scenario "Arrêt d'une instance applicative" "docker stop $APP1 >/dev/null" "docker start $APP1 >/dev/null"
PG=$(primary_pg); [ -n "$PG" ] || PG=pg1
scenario "Arrêt du primaire PostgreSQL ($PG) : promotion Patroni" "$DC stop $PG >/dev/null" "$DC start $PG >/dev/null"
scenario "Arrêt d'un nœud RabbitMQ (rabbitmq2)" "$DC stop rabbitmq2 >/dev/null" "$DC start rabbitmq2 >/dev/null"
scenario "Arrêt du primaire Redis (Sentinel)" "$DC stop redis >/dev/null" "$DC start redis >/dev/null"
kill $PROBE 2>/dev/null; sleep 120   # laisse les MT acceptés aller jusqu'à l'accusé

ACCEPTED=$(awk '($2 == 202 || $2 == 200) && $3 != "-" { print $3 }' "$LOG" | sort -u)
TOTAL=$(printf '%s\n' "$ACCEPTED" | grep -c . || true); LOST=0
for id in $ACCEPTED; do
  st=$(curl -s -H "X-API-Key: $API_KEY" "$BASE/api/v1/messages/$id" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')
  case "$st" in DELIVERED|SUBMITTED|PENDING) ;; *) LOST=$((LOST + 1)); echo "MT $id : statut ${st:-introuvable}" ;; esac
done
ATTEMPTS=$(wc -l < "$LOG"); FAILED=$(awk '$2 != 202 && $2 != 200' "$LOG" | wc -l)
{
  echo "# Exercice de bascule HA — $RUN"; echo
  echo "Plateforme : $BASE · seuil d'interruption accepté : $MAX_OUTAGE s par scénario"; echo
  echo "| Scénario | Plus longue interruption de l'API | Résultat |"; echo "|---|---|---|"
  printf '%s\n' "${ROWS[@]}"; echo
  echo "Tentatives d'envoi : $ATTEMPTS · refusées pendant les bascules (à réessayer par le client) : $FAILED · acceptées : $TOTAL · **perdues après acceptation : $LOST**"; echo
  echo "Critère : chaque interruption ≤ $MAX_OUTAGE s et aucun MT accepté perdu."
} > "$OUT"
cat "$OUT"
[ "$LOST" -eq 0 ] && ! grep -q 'ÉCHEC' "$OUT"
