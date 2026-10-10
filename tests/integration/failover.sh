#!/usr/bin/env bash
# Bascule multi-liens SMPP (SMPP-008) sur Jasmin RÉEL : deux liaisons vers le simulateur (127.0.0.1 et 127.0.0.2), route FailoverMTRoute.
# Prérequis : pile démarrée par local-stack.sh, puis reprovisionnée avec  TT_SMSC_HOST_2=127.0.0.2  (voir docs/14-tests-integration.md).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; J="python3 $here/jcli.py"; SIM="${SIM:-http://localhost:8081}"; fail=0
JU="${JASMIN_HTTP:-http://127.0.0.1:1401}"; JP="${JASMIN_PASSWORD:-Zx9jasminpass01}"
check() { if [ "$2" = "0" ]; then echo "OK    $1"; else echo "ECHEC $1"; fail=1; fi; }
submitted() { curl -s "$SIM/stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["submitted"])'; }
send() { curl -s -o /tmp/fo.out -w '%{http_code}' "$JU/send?username=vas_tt&password=$JP&to=2169855500$1&from=85500&content=failover$1"; }
$J "smppccm -1 smppc_tt" "smppccm -1 smppc_tt_2" >/dev/null; sleep 3
b=$(submitted); [ "$(send 1)" = 200 ]; check "deux liens : MT accepté" $?; sleep 2; [ "$(submitted)" -eq $((b+1)) ]; check "deux liens : reçu par le SMSC" $?
$J "smppccm -0 smppc_tt" >/dev/null; sleep 3
b=$(submitted); [ "$(send 2)" = 200 ]; check "lien 1 arrêté : MT accepté via le lien 2" $?; sleep 2; [ "$(submitted)" -eq $((b+1)) ]; check "lien 1 arrêté : reçu par le SMSC" $?
$J "smppccm -1 smppc_tt" "smppccm -0 smppc_tt_2" >/dev/null; sleep 4
b=$(submitted); [ "$(send 3)" = 200 ]; check "lien 2 arrêté : MT accepté via le lien 1" $?; sleep 2; [ "$(submitted)" -eq $((b+1)) ]; check "lien 2 arrêté : reçu par le SMSC" $?
$J "smppccm -0 smppc_tt" >/dev/null; sleep 3
[ "$(send 4)" = 412 ]; check "aucun lien : Jasmin répond 412 (l'application réessaie, voir JasminHttpGatewayTests)" $?
$J "smppccm -1 smppc_tt" "smppccm -1 smppc_tt_2" >/dev/null
exit $fail
