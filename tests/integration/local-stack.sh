#!/usr/bin/env bash
# Démarre en local (sans Docker) la VRAIE pile utilisée par tests/integration/full-stack.mjs :
#   PostgreSQL 16 · Redis · RabbitMQ · Jasmin 0.10.13 (pip) + ses 3 démons · simulateur SMSC SMPP · application (profil pro)
# TOUTE la configuration vient d'UN fichier .env (ENVF, défaut /tmp/vas-it.env) créé par tools/init-env.py : secrets générés, puis valeurs « externes »
# de test posées par setenv. Application, Jasmin, provisionnement et sauvegardes lisent ce même fichier (infra/lib/env.sh) : c'est la configuration de production.
# Prérequis (exemple Ubuntu 24.04) : apt install postgresql-16 redis-server rabbitmq-server netcat-openbsd python3.11 python3.11-venv
#   python3.11 -m venv ~/jvenv && ~/jvenv/bin/pip install jasmin==0.10.13        # NE PAS utiliser 0.11.x (voir docs/14-tests-integration.md)
#   mvn -DskipTests package ; fichier amqp0-9-1.xml de Jasmin dans $JROOT/etc/jasmin/resource/ (dépôt jookies/jasmin, misc/config/resource)
# Usage : tests/integration/local-stack.sh up|down|test|stop-app|start-app      (JVENV=/chemin/venv)
set -euo pipefail
JVENV="${JVENV:-$HOME/jvenv}"; JROOT="${JROOT:-/tmp/jroot}"; PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"
PGPORT=5433; REDIS_APP=6380; REDIS_JASMIN=6379; APP_PORT=18090; SIM_CTL=8081; SIM_SMPP=2776
HERE="$(cd "$(dirname "$0")/../.." && pwd)"; JAR="$HERE/target/vas-platform-1.0.0.jar"
ADMIN_PW="${ADMIN_PW:-Admin-Real-Pass-42}"
export ENV_FILE="${ENVF:-/tmp/vas-it.env}"; DATA_DIR="${DATA_DIR:-/tmp/vas-it-data}"

setenv() {   # setenv CLE VALEUR : pose une valeur dans le .env de test (valeur littérale entre apostrophes si elle contient $)
  python3 - "$ENV_FILE" "$1" "$2" <<'PY'
import re, sys
f, k, v = sys.argv[1:4]
s = open(f).read()
val = f"'{v}'" if "$" in v else v
if re.search(rf"^{k}=", s, re.M): s = re.sub(rf"^{k}=[^\n]*", lambda m: f"{k}={val}", s, flags=re.M)
else: s += f"\n{k}={val}\n"
open(f, "w").write(s)
PY
}

prepare_env() {
  if [ ! -f "$ENV_FILE" ]; then
    python3 "$HERE/tools/init-env.py" --out "$ENV_FILE" > /tmp/init-env.out
    HASH="$(java -Dloader.main=tn.vas.tools.HashPassword -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$ADMIN_PW" 2>/dev/null)"
    setenv ADMIN_PASSWORD_HASH "$HASH"
  fi
  mkdir -p "$DATA_DIR"
  printf 'prefix;operator\n# plage de test chargée par ROUTING_RANGES_FILE\n7777;ORANGE\n' > "$DATA_DIR/ranges.csv"
  printf 'msisdn;operator\n77771234;OOREDOO\n' > "$DATA_DIR/ported.csv"
  cat > "$DATA_DIR/catalog.json" <<'JSON'
{"partners":[{"name":"Partenaire Catalogue","sharePercent":20}],
 "services":[{"name":"Service Catalogue","type":"VOTE","operator":"TT","shortCode":"85503","partner":"Partenaire Catalogue","keywords":["CAT"],
   "tariffs":[{"eventType":"MT","grossAmount":0.5,"operatorPercent":40,"taxPercent":19}]}]}
JSON
  # valeurs « externes » de la pile locale (en production : fournies par le client / les opérateurs)
  setenv DB_URL "jdbc:postgresql://localhost:$PGPORT/vas"; setenv READ_DB_URL "jdbc:postgresql://localhost:$PGPORT/vas"
  setenv RABBIT_HOST localhost; setenv REDIS_HOST localhost; setenv REDIS_PORT "$REDIS_APP"
  setenv JASMIN_URL http://127.0.0.1:1401; setenv VAS_PUBLIC_BASE_URL "http://vasapp.local:$APP_PORT"
  setenv TT_SMSC_HOST 127.0.0.1; setenv TT_SMSC_PORT "$SIM_SMPP"; setenv TT_SYSTEM_ID sim; setenv TT_SMSC_PASSWORD sim
  setenv TT_SHORT_CODES "85500,85503"; setenv TT_MSISDN_PREFIXES "9,4"; setenv TT_TPS 50
  setenv ROUTING_RANGES_FILE "$DATA_DIR/ranges.csv"; setenv ROUTING_PORTED_FILE "$DATA_DIR/ported.csv"; setenv CATALOG_FILE "$DATA_DIR/catalog.json"
  setenv DATA_KEY "${DATA_KEY:-$(grep -m1 '^DATA_KEY=' "$ENV_FILE" | cut -d= -f2 | cut -d' ' -f1)}"
  # shellcheck disable=SC1091
  source "$HERE/infra/lib/env.sh"
}

stop_all() {
  for pat in '[v]as-platform-1.0.0.jar --server.port=18090' '[S]mppSimulator' '[j]asmind' '[d]eliversmd' '[d]lrd.py' '[d]lrlookupd'; do
    for p in $(ps -eo pid,args | awk "/$pat/{print \$1}"); do kill "$p" 2>/dev/null || true; done
  done
  sleep 1; rm -f /tmp/jasmind* /tmp/dlrd-master.lock* /tmp/dlrlookupd-master.lock* /tmp/deliversmd* 2>/dev/null || true   # verrous périmés d'un arrêt brutal ("Lock not acquired")
}

start_app() {
  [ -n "${REDIS_PASSWORD:-}" ] || { prepare_env; }
  (nohup java -jar "$JAR" --server.port=$APP_PORT > /tmp/app-pro.log 2>&1 &)   # tout vient de l'environnement chargé depuis le .env
  for _ in $(seq 1 40); do sleep 3; [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:$APP_PORT/auth/env)" = 200 ] && break; done
}

stop_app() {
  for p in $(ps -eo pid,args | awk '/[v]as-platform-1.0.0.jar --server.port=18090/{print $1}'); do kill "$p" 2>/dev/null || true; done
  sleep 3
}

up() {
  stop_all; sleep 2
  prepare_env
  command -v rabbitmqctl >/dev/null && (rabbitmqctl -q status >/dev/null 2>&1 || (rabbitmq-server -detached; sleep 20))
  redis-cli -p $REDIS_JASMIN ping >/dev/null 2>&1 || redis-server --port $REDIS_JASMIN --daemonize yes >/dev/null
  redis-cli -p $REDIS_APP ping >/dev/null 2>&1 || redis-server --port $REDIS_APP --daemonize yes >/dev/null
  "$PGBIN/pg_isready" -p $PGPORT -h /tmp >/dev/null || { echo "PostgreSQL ($PGPORT) injoignable : le démarrer (pg_ctl) et créer le rôle vas" >&2; exit 1; }
  # les mots de passe du .env sont appliqués aux services locaux (en Docker : fournis directement par docker-compose.yml)
  psql -d postgres -h /tmp -p $PGPORT -U postgres -c "alter role vas password '$DB_PASSWORD'" -c "drop database if exists vas" -c "create database vas owner vas" >/dev/null
  for port in $REDIS_JASMIN $REDIS_APP; do
    REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli -p $port flushall >/dev/null 2>&1 || redis-cli -p $port flushall >/dev/null
    redis-cli -p $port config set requirepass "$REDIS_PASSWORD" >/dev/null
  done
  rabbitmqctl -q add_user "$RABBIT_USER" "$RABBIT_PASSWORD" 2>/dev/null || rabbitmqctl -q change_password "$RABBIT_USER" "$RABBIT_PASSWORD" >/dev/null
  rabbitmqctl -q set_permissions -p / "$RABBIT_USER" ".*" ".*" ".*" >/dev/null; rabbitmqctl -q set_user_tags "$RABBIT_USER" administrator >/dev/null
  rm -rf "$JROOT"/etc/jasmin/store/*; mkdir -p "$JROOT"/etc/jasmin/store "$JROOT"/var/log/jasmin
  grep -q vasapp.local /etc/hosts || echo "127.0.0.1 vasapp.local" >> /etc/hosts   # Jasmin refuse un hôte sans point
  (SIM_SYSTEM_ID=sim SIM_PASSWORD=sim SIM_SMPP_PORT=$SIM_SMPP SIM_CONTROL_PORT=$SIM_CTL SIM_DLR_DELAY_MS=300 nohup java -Dloader.main=tn.vas.smppsim.SmppSimulator -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher > /tmp/sim.log 2>&1 &)
  # Jasmin reçoit EXACTEMENT les variables du service « jasmin » de docker-compose.yml (même noms, mêmes valeurs tirées du .env)
  export REDIS_CLIENT_HOST=127.0.0.1 REDIS_CLIENT_PORT=$REDIS_JASMIN REDIS_CLIENT_PASSWORD="$REDIS_PASSWORD" AMQP_BROKER_HOST=127.0.0.1 AMQP_BROKER_PORT=5672 \
         AMQP_BROKER_USERNAME="$RABBIT_USER" AMQP_BROKER_PASSWORD="$RABBIT_PASSWORD" JCLI_ADMIN_USERNAME="${JCLI_USER:-jcliadmin}" JCLI_ADMIN_PASSWORD="$JCLI_PASSWORD_MD5" ENABLE_PUBLISH_SUBMIT_SM_RESP=1
  (ROOT_PATH=$JROOT nohup "$JVENV/bin/jasmind.py" > /tmp/jasmin.log 2>&1 &)
  for d in deliversmd dlrd dlrlookupd; do (ROOT_PATH=$JROOT nohup "$JVENV/bin/$d.py" > /tmp/$d.log 2>&1 &); done
  sleep 15
  # provisionnement : lit le .env (mêmes variables qu'en production) ; seuls l'hôte jcli et l'URL de rappel sont posés comme le fait le compose
  JCLI_HOST=127.0.0.1 JCLI_PORT=8990 APP_URL="$VAS_PUBLIC_BASE_URL" "$HERE/infra/jasmin/provision.sh" > /tmp/provision.out
  start_app
  echo "pile prête : app :$APP_PORT, simulateur :$SIM_CTL, Jasmin jcli :8990 / http :1401 (configuration : $ENV_FILE)"
}

case "${1:-}" in
  up) up ;;
  down) stop_all ;;
  stop-app) stop_app ;;
  start-app) prepare_env; start_app ;;
  test) up; BASE=http://localhost:$APP_PORT SIM=http://localhost:$SIM_CTL ADMIN_USER=admin ADMIN_PASSWORD="$ADMIN_PW" node "$HERE/tests/integration/full-stack.mjs" ;;
  *) echo "usage : $0 up|down|test|stop-app|start-app" >&2; exit 2 ;;
esac
