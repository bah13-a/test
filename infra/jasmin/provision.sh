#!/usr/bin/env bash
# Provisionne Jasmin : groupe, callback HTTP MO, puis un connecteur SMPP + utilisateur + route MT par opérateur.
# Variables par opérateur (préfixe TT_, ORANGE_, OOREDOO_) : SMSC_HOST [SMSC_HOST_2..4 LINK_MODE=failover|roundrobin] SMSC_PORT SYSTEM_ID SMSC_PASSWORD [SYSTEM_TYPE BIND_MODE SRC_TON SRC_NPI DST_TON DST_NPI ELINK TPS]
# Variables globales : JASMIN_PASSWORD CALLBACK_SECRET [JCLI_HOST=jasmin JCLI_PORT=8990 JCLI_USER=jcliadmin JCLI_PASSWORD APP_URL=http://app:8080 DRY_RUN=1]
# Un opérateur dont SMSC_HOST est vide est ignoré. Usage : DRY_RUN=1 ./provision.sh | ./provision.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${JASMIN_PASSWORD:?}" "${CALLBACK_SECRET:?}"
APP_URL="${APP_URL:-http://app.vas.internal:8080}"
# Jasmin n'accepte pour l'URL de callback qu'un domaine AVEC point, localhost ou une IP (validé sur Jasmin 0.11) : "http://app:8080" est refusé.
host="${APP_URL#*://}"; host="${host%%[:/]*}"
if [[ "$host" != *.* && "$host" != "localhost" ]]; then
  echo "ERREUR : APP_URL=$APP_URL - Jasmin refuse un nom d'hôte sans point. Utiliser un alias réseau avec point (ex. app.vas.internal) ou une IP." >&2
  exit 2
fi
# rendu du gabarit sans dépendance (envsubst absent de certaines images)
render() {
  local line n
  while IFS= read -r line || [[ -n "$line" ]]; do
    while [[ "$line" =~ \$\{([A-Z_]+)\} ]]; do n="${BASH_REMATCH[1]}"; line="${line//\$\{$n\}/${!n}}"; done
    printf '%s\n' "$line"
  done
}
out="$(mktemp)"; trap 'rm -f "$out"' EXIT

cat >> "$out" <<JCLI
group -a
gid vas
ok
httpccm -a
cid vas_app
url ${APP_URL}/callbacks/mo?secret=${CALLBACK_SECRET}
method POST
ok
morouter -a
type DefaultRoute
connector http(vas_app)
ok
JCLI

order=10
for op in TT ORANGE OOREDOO; do
  host_var="${op}_SMSC_HOST"
  [[ -n "${!host_var:-}" ]] || { echo "# ${op} ignoré (${host_var} vide)" >&2; continue; }
  get() { local v="${op}_$1"; echo "${!v:-${2:-}}"; }
  export OPL="${op,,}" SMSC_PORT="$(get SMSC_PORT 2775)" SYSTEM_ID="$(get SYSTEM_ID)" SMSC_PASSWORD="$(get SMSC_PASSWORD)" \
         SYSTEM_TYPE="$(get SYSTEM_TYPE VAS)" BIND_MODE="$(get BIND_MODE transceiver)" SRC_TON="$(get SRC_TON 3)" SRC_NPI="$(get SRC_NPI 0)" \
         DST_TON="$(get DST_TON 1)" DST_NPI="$(get DST_NPI 1)" ELINK="$(get ELINK 30)" TPS="$(get TPS 50)" ORDER="$order" JASMIN_PASSWORD
  # liaisons : <OP>_SMSC_HOST (principale) puis <OP>_SMSC_HOST_2 .. _4 (secours ou répartition, même compte SMPP)
  links=(); connectors=()
  for n in 1 2 3 4; do
    suffix=""; [[ $n -gt 1 ]] && suffix="_$n"
    h="$(get "SMSC_HOST$suffix")"; [[ -n "$h" ]] || continue
    export CID="${OPL}${suffix}" LINK_HOST="$h"
    render < "$here/connector.jcli.tpl" >> "$out"
    connectors+=("smppc(smppc_${CID})")
  done
  if [[ ${#connectors[@]} -eq 1 ]]; then export ROUTE_TYPE=StaticMTRoute ROUTE_CONNECTORS="connector ${connectors[0]}"
  elif [[ "$(get LINK_MODE failover)" == "roundrobin" ]]; then export ROUTE_TYPE=RandomRoundrobinMTRoute ROUTE_CONNECTORS="connectors $(IFS=';'; echo "${connectors[*]}")"
  else export ROUTE_TYPE=FailoverMTRoute ROUTE_CONNECTORS="connectors $(IFS=';'; echo "${connectors[*]}")"; fi
  render < "$here/operator.jcli.tpl" >> "$out"
  order=$((order + 10))
done

# jcli rejette les lignes de commentaire/vides en mode interactif : on ne garde que les commandes
grep -vE '^[[:space:]]*(#|$)' "$out" > "$out.clean" && mv "$out.clean" "$out"

if [[ "${DRY_RUN:-0}" == "1" ]]; then sed -E 's/^(password|username) .*/\1 ***/' "$out"; exit 0; fi
# jcli est un shell interactif : on envoie une commande, puis on ATTEND le retour du prompt ("jcli : " ou "> " en mode saisie)
# avant la suivante. Les temporisations fixes se sont révélées fragiles (validé contre Jasmin 0.11 réel).
exec 3<>"/dev/tcp/${JCLI_HOST:-jasmin}/${JCLI_PORT:-8990}" || { echo "ERREUR : jcli injoignable" >&2; exit 1; }
transcript=""
wait_for() {   # $1 = suffixe attendu (regex), $2 = délai max en secondes
  local buf="" ch
  while IFS= read -r -t "${2:-20}" -n 1 -u 3 ch || [[ -n "$ch" ]]; do
    buf+="$ch"
    [[ "$buf" =~ $1$ ]] && { transcript+="$buf"; return 0; }
    ch=""
  done
  transcript+="$buf"; echo "ERREUR : délai dépassé en attendant '$1' (reçu : ${buf: -120})" >&2; return 1
}
send_line() { printf '%s\r\n' "$1" >&3; transcript+=$'\n'"<< $1"$'\n'; }

wait_for 'Authentication required\.' 10 || exit 1
send_line "${JCLI_USER:-jcliadmin}"
wait_for 'Password: ' 10 || exit 1
send_line "${JCLI_PASSWORD:?JCLI_PASSWORD requis}"
wait_for 'jcli : ' 10 || { echo "ERREUR : authentification jcli refusée" >&2; exit 1; }
while IFS= read -r line; do
  send_line "$line"
  if [[ "$line" == "ok" || "$line" == persist || "$line" == smppccm\ -1* ]]; then wait_for 'jcli : ' 40 || exit 1; else wait_for '(jcli : |> )' 20 || exit 1; fi
done < "$out"
send_line quit
exec 3>&-
echo "$transcript" | tr -d '\r'
if echo "$transcript" | grep -qE "Unknown .* key|Incorrect command|Error:"; then
  echo "ERREUR : jcli a rejeté une commande (voir ci-dessus)" >&2
  exit 1
fi
