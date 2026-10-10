#!/usr/bin/env bash
# Démarre en local (sans Docker) la VRAIE pile utilisée par tests/integration/full-stack.mjs :
#   PostgreSQL 16 · Redis · RabbitMQ · Jasmin 0.10.13 (pip) + ses 3 démons · simulateur SMSC SMPP · application (profil pro)
# Prérequis (exemple Ubuntu 24.04) : apt install postgresql-16 redis-server rabbitmq-server netcat-openbsd python3.11 python3.11-venv
#   python3.11 -m venv ~/jvenv && ~/jvenv/bin/pip install jasmin==0.10.13        # NE PAS utiliser 0.11.x (voir docs/14-tests-integration.md)
#   mvn -DskipTests package ; fichier amqp0-9-1.xml de Jasmin dans $JROOT/etc/jasmin/resource/ (dépôt jookies/jasmin, misc/config/resource)
# Usage : tests/integration/local-stack.sh up|down|test      (variables en tête de fichier modifiables)
set -euo pipefail
JVENV="${JVENV:-$HOME/jvenv}"; JROOT="${JROOT:-/tmp/jroot}"; PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"
PGPORT=5433; REDIS_APP=6380; REDIS_JASMIN=6379; APP_PORT=18090; SIM_CTL=8081; SIM_SMPP=2776
HERE="$(cd "$(dirname "$0")/../.." && pwd)"; JAR="$HERE/target/vas-platform-1.0.0.jar"
ADMIN_PW="${ADMIN_PW:-Admin-Real-Pass-42}"
JASMIN_PASSWORD=Zx9jasminpass01; CALLBACK_SECRET=Zx9-callback-secret-very-long-0123

stop_all() {
  for pat in '[v]as-platform-1.0.0.jar --server.port=18090' '[S]mppSimulator' '[j]asmind' '[d]eliversmd' '[d]lrd.py' '[d]lrlookupd'; do
    for p in $(ps -eo pid,args | awk "/$pat/{print \$1}"); do kill "$p" 2>/dev/null || true; done
  done
  sleep 1; rm -f /tmp/jasmind* /tmp/dlrd-master.lock* /tmp/dlrlookupd-master.lock* /tmp/deliversmd* 2>/dev/null || true   # verrous périmés d'un arrêt brutal ("Lock not acquired")
}

up() {
  stop_all; sleep 2
  command -v rabbitmqctl >/dev/null && (rabbitmqctl -q status >/dev/null 2>&1 || (rabbitmq-server -detached; sleep 20))
  redis-cli -p $REDIS_JASMIN ping >/dev/null 2>&1 || redis-server --port $REDIS_JASMIN --daemonize yes >/dev/null
  redis-cli -p $REDIS_APP ping >/dev/null 2>&1 || redis-server --port $REDIS_APP --daemonize yes >/dev/null
  "$PGBIN/pg_isready" -p $PGPORT -h /tmp >/dev/null || { echo "PostgreSQL ($PGPORT) injoignable : le démarrer (pg_ctl) et créer le rôle vas" >&2; exit 1; }
  psql -h /tmp -p $PGPORT -U postgres -c "drop database if exists vas" -c "create database vas owner vas" >/dev/null
  redis-cli -p $REDIS_JASMIN flushall >/dev/null; redis-cli -p $REDIS_APP flushall >/dev/null
  rm -rf "$JROOT"/etc/jasmin/store/*; mkdir -p "$JROOT"/etc/jasmin/store "$JROOT"/var/log/jasmin
  grep -q vasapp.local /etc/hosts || echo "127.0.0.1 vasapp.local" >> /etc/hosts   # Jasmin refuse un hôte sans point
  (SIM_SYSTEM_ID=sim SIM_PASSWORD=sim SIM_SMPP_PORT=$SIM_SMPP SIM_CONTROL_PORT=$SIM_CTL SIM_DLR_DELAY_MS=300 nohup java -Dloader.main=tn.vas.smppsim.SmppSimulator -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher > /tmp/sim.log 2>&1 &)
  (ROOT_PATH=$JROOT nohup "$JVENV/bin/jasmind.py" > /tmp/jasmin.log 2>&1 &)
  for d in deliversmd dlrd dlrlookupd; do (ROOT_PATH=$JROOT nohup "$JVENV/bin/$d.py" > /tmp/$d.log 2>&1 &); done
  sleep 15
  TT_SMSC_HOST=127.0.0.1 TT_SMSC_PORT=$SIM_SMPP TT_SYSTEM_ID=sim TT_SMSC_PASSWORD=sim JASMIN_PASSWORD=$JASMIN_PASSWORD CALLBACK_SECRET=$CALLBACK_SECRET \
    APP_URL=http://vasapp.local:$APP_PORT JCLI_HOST=127.0.0.1 JCLI_PORT=8990 JCLI_USER=jcliadmin JCLI_PASSWORD=jclipwd "$HERE/infra/jasmin/provision.sh" > /tmp/provision.out
  HASH="$(java -Dloader.main=tn.vas.tools.HashPassword -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$ADMIN_PW" 2>/dev/null)"
  (SPRING_PROFILES_ACTIVE=pro DB_URL=jdbc:postgresql://localhost:$PGPORT/vas DB_USER=vas DB_PASSWORD=Zx9-db-real-password RABBIT_HOST=localhost RABBIT_USER=guest RABBIT_PASSWORD=guest \
    REDIS_HOST=localhost REDIS_PORT=$REDIS_APP VAS_PUBLIC_BASE_URL=http://vasapp.local:$APP_PORT DATA_KEY=Zx9-data-key-integration-0123456789abcdefghijklmnop TOKEN_SECRET=Zx9-token-secret-very-long-random-0123456789 JASMIN_URL=http://127.0.0.1:1401 \
    JASMIN_PASSWORD=$JASMIN_PASSWORD CALLBACK_SECRET=$CALLBACK_SECRET ADMIN_PASSWORD_HASH="$HASH" TT_MSISDN_PREFIXES="9,4" TT_SHORT_CODES="85500,85503" TT_TPS=50 \
    nohup java -jar "$JAR" --server.port=$APP_PORT > /tmp/app-pro.log 2>&1 &)
  for _ in $(seq 1 40); do sleep 3; [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:$APP_PORT/auth/env)" = 200 ] && break; done
  echo "pile prête : app :$APP_PORT, simulateur :$SIM_CTL, Jasmin jcli :8990 / http :1401"
}

case "${1:-}" in
  up) up ;;
  down) stop_all ;;
  test) up; BASE=http://localhost:$APP_PORT SIM=http://localhost:$SIM_CTL ADMIN_USER=admin ADMIN_PASSWORD="$ADMIN_PW" node "$HERE/tests/integration/full-stack.mjs" ;;
  *) echo "usage : $0 up|down|test" >&2; exit 2 ;;
esac
