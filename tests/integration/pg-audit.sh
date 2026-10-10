#!/usr/bin/env bash
# Vérifie sur PostgreSQL réel que le journal d'audit est immuable (V10) : UPDATE/TRUNCATE interdits, DELETE seulement dans la transaction de purge.
# Usage : PGHOST=/tmp PGPORT=5433 PGUSER=postgres tests/integration/pg-audit.sh [base=vas]
set -uo pipefail
DB="${1:-vas}"; fail=0
q() { psql -d "$DB" -v ON_ERROR_STOP=1 -At -c "$1" 2>&1; }
expect_error() { local label="$1" sql="$2" out; out="$(q "$sql")"; if echo "$out" | grep -qi "audit"; then echo "OK    $label : refusé"; else echo "ECHEC $label : accepté ($out)"; fail=1; fi; }
q "insert into audit_log(at, actor, action, target, detail) values (now(), 'pg-test', 'PG_AUDIT_TEST', 'x', 'y')" >/dev/null
expect_error "UPDATE" "update audit_log set actor='pirate' where action='PG_AUDIT_TEST'"
expect_error "DELETE hors purge" "delete from audit_log where action='PG_AUDIT_TEST'"
expect_error "TRUNCATE" "truncate audit_log"
n="$(q "select count(*) from audit_log where action='PG_AUDIT_TEST' and actor='pg-test'")"
[ "$n" = "1" ] && echo "OK    la ligne est intacte" || { echo "ECHEC ligne modifiée ($n)"; fail=1; }
out="$(psql -d "$DB" -v ON_ERROR_STOP=1 -At -c "begin; select set_config('vas.audit_purge','on',true); delete from audit_log where action='PG_AUDIT_TEST'; commit;" 2>&1)"
n="$(q "select count(*) from audit_log where action='PG_AUDIT_TEST'")"
[ "$n" = "0" ] && echo "OK    DELETE autorisé dans la transaction de purge (vas.audit_purge=on)" || { echo "ECHEC purge refusée ($out)"; fail=1; }
exit $fail
