#!/usr/bin/env bash
# Provisionne Jasmin : groupe, callback HTTP MO, puis un connecteur SMPP + utilisateur + route MT par opérateur.
# Variables par opérateur (préfixe TT_, ORANGE_, OOREDOO_) : SMSC_HOST SMSC_PORT SYSTEM_ID SMSC_PASSWORD [SYSTEM_TYPE BIND_MODE SRC_TON SRC_NPI DST_TON DST_NPI ELINK TPS]
# Variables globales : JASMIN_PASSWORD CALLBACK_SECRET [JCLI_HOST=jasmin JCLI_PORT=8990 JCLI_USER=jcliadmin JCLI_PASSWORD APP_URL=http://app:8080 DRY_RUN=1]
# Un opérateur dont SMSC_HOST est vide est ignoré. Usage : DRY_RUN=1 ./provision.sh | ./provision.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${JASMIN_PASSWORD:?}" "${CALLBACK_SECRET:?}"
APP_URL="${APP_URL:-http://app:8080}"
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
rate 0.0
ok
JCLI

order=10
for op in TT ORANGE OOREDOO; do
  host_var="${op}_SMSC_HOST"
  [[ -n "${!host_var:-}" ]] || { echo "# ${op} ignoré (${host_var} vide)" >&2; continue; }
  get() { local v="${op}_$1"; echo "${!v:-${2:-}}"; }
  export OPL="${op,,}" SMSC_HOST="$(get SMSC_HOST)" SMSC_PORT="$(get SMSC_PORT 2775)" SYSTEM_ID="$(get SYSTEM_ID)" SMSC_PASSWORD="$(get SMSC_PASSWORD)" \
         SYSTEM_TYPE="$(get SYSTEM_TYPE VAS)" BIND_MODE="$(get BIND_MODE transceiver)" SRC_TON="$(get SRC_TON 3)" SRC_NPI="$(get SRC_NPI 0)" \
         DST_TON="$(get DST_TON 1)" DST_NPI="$(get DST_NPI 1)" ELINK="$(get ELINK 30)" TPS="$(get TPS 50)" ORDER="$order" JASMIN_PASSWORD
  render < "$here/operator.jcli.tpl" >> "$out"
  order=$((order + 10))
done

if [[ "${DRY_RUN:-0}" == "1" ]]; then sed -E 's/^(password|username) .*/\1 ***/' "$out"; exit 0; fi
# jcli est un shell telnet ; nc envoie le script puis ferme
{ sleep 1; echo "${JCLI_USER:-jcliadmin}"; sleep 1; echo "${JCLI_PASSWORD:?JCLI_PASSWORD requis}"; sleep 1; cat "$out"; sleep 2; echo quit; } | nc "${JCLI_HOST:-jasmin}" "${JCLI_PORT:-8990}"
