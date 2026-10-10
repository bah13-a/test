#!/usr/bin/env bash
# Mesure avant/après les index (V7) sur un volume réaliste. Usage : PGHOST=/tmp PGPORT=5433 PGUSER=postgres tests/perf/db-bench.sh [lignes_mt=2000000]
set -euo pipefail
N="${1:-2000000}"; DB=vasbench; M="$(cd "$(dirname "$0")/../../src/main/resources/db/migration" && pwd)"
psql -qAt -c "drop database if exists $DB" -c "create database $DB" >/dev/null
for f in V1__init V2__seed_tunisia V3__webhook_outbox V4__users_i18n V5__blacklist V6__operator_config_applied; do psql -q -d $DB -f "$M/$f.sql" >/dev/null; done
psql -q -d $DB <<SQL
insert into vas_service (name, type, status, short_code_id) select 'bench','VOTE','ACTIVE', 1 where false;
insert into short_code (number, operator_id) values ('99999', 1);
insert into vas_service (name, type, status, short_code_id) values ('bench','VOTE','ACTIVE', 1);
-- $N MT sur 30 jours, 10 000 numéros distincts ; statuts réalistes
insert into mt_message (correlation_id, operator_id, service_id, msisdn, sender, content, encoding, segments, priority, status, attempts, billable, created_at, updated_at)
select 'c' || g, 1 + (g % 3), 1, '+2169' || lpad((g % 10000000)::text, 7, '0'), '99999', 'merci', 'GSM7', 1, 'TRANSACTIONAL',
       case when g % 1000 = 0 then 'PENDING' when g % 50 = 0 then 'SUBMITTED' when g % 20 = 0 then 'UNDELIVERABLE' else 'DELIVERED' end, 1, true,
       now() - (g % 2592000) * interval '1 second', now() - (g % 2592000) * interval '1 second'
from generate_series(1, $N) g;
insert into mo_message (operator_id, dedup_key, msisdn, short_code, content, received_at, service_id, outcome)
select 1 + (g % 3), 'id:' || g, '+2169' || lpad((g % 10000000)::text, 7, '0'), '99999', 'VOTE A', now() - (g % 2592000) * interval '1 second', 1, 'ROUTED' from generate_series(1, $N) g;
insert into ledger_event (event_id, event_type, operator_id, service_id, short_code, msisdn, gross_amount, operator_share, provider_share, partner_share, taxes, billing_status, created_at, updated_at)
select 'MT-' || g, 'MT', 1 + (g % 3), 1, '99999', '+2169' || lpad((g % 10000000)::text, 7, '0'), 0.5, 0.2, 0.2, 0.05, 0.05,
       case when g % 10 = 0 then 'PENDING' else 'CHARGED' end, now() - (g % 2592000) * interval '1 second', now() from generate_series(1, $N / 2) g;
insert into audit_log (actor, action, target, at) select 'u', 'A', 't', now() - g * interval '1 second' from generate_series(1, 200000) g;
analyze;
SQL
cat > /tmp/bench.sql <<'SQL'
\set ON_ERROR_STOP on
\echo 'Q1 recherche de messages par numéro (50 dernières)'
explain (analyze, timing off, summary on) select * from mt_message where msisdn = '+21695000042' order by created_at desc limit 50;
\echo 'Q2 tableau de bord : MT par statut sur 24 h'
explain (analyze, timing off, summary on) select status, count(*) from mt_message where created_at >= now() - interval '24 hours' group by status;
\echo 'Q3 balayeur : MT PENDING anciens'
explain (analyze, timing off, summary on) select * from mt_message where status = 'PENDING' and created_at < now() - interval '30 seconds';
\echo 'Q4 expiration DLR : SUBMITTED non mis à jour'
explain (analyze, timing off, summary on) select * from mt_message where status = 'SUBMITTED' and updated_at < now() - interval '72 hours' limit 500;
\echo 'Q5 grand livre : page CHARGED sur 7 jours'
explain (analyze, timing off, summary on) select * from ledger_event where billing_status = 'CHARGED' and created_at >= now() - interval '7 days' order by created_at desc limit 50;
\echo 'Q6 dédoublonnage MO par contenu (fenêtre 30 s)'
explain (analyze, timing off, summary on) select count(*) > 0 from mo_message where operator_id = 1 and msisdn = '+21695000042' and short_code = '99999' and received_at > now() - interval '30 seconds';
\echo 'Q7 comptage des MO par issue sur 24 h'
explain (analyze, timing off, summary on) select outcome, count(*) from mo_message where received_at >= now() - interval '24 hours' group by outcome;
\echo 'Q8 journal d audit : page la plus récente'
explain (analyze, timing off, summary on) select * from audit_log order by id desc limit 50;
\echo 'Q9 messages d une API : idempotence clientRef'
explain (analyze, timing off, summary on) select * from mt_message where api_client_id = 5 and client_ref = 'ref-1';
SQL
echo "=== AVANT index V7 ==="; psql -q -d $DB -f /tmp/bench.sql | grep -E "^Q|Execution Time" | paste - - | sed 's/Execution Time://'
psql -q -d $DB -f "$M/V7__indexes.sql" >/dev/null; psql -q -d $DB -c "analyze" >/dev/null
echo "=== APRÈS index V7 ==="; psql -q -d $DB -f /tmp/bench.sql | grep -E "^Q|Execution Time" | paste - - | sed 's/Execution Time://'
psql -At -d $DB -c "select 'taille base : ' || pg_size_pretty(pg_database_size('$DB'))" -c "select 'dont index : ' || pg_size_pretty(sum(pg_relation_size(indexrelid))) from pg_index where indrelid::regclass::text in ('mt_message','mo_message','ledger_event')"
