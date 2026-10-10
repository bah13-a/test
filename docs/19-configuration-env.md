# Configuration : un seul fichier `.env`

Toutes les valeurs réelles (secrets, opérateurs, domaine, alertes, sauvegardes, réglages) se saisissent **dans un seul fichier**, `.env`, à la racine du dépôt. Aucun autre fichier de secrets n'est nécessaire : `docker-compose.yml`, l'application, Jasmin, la passerelle TLS, Prometheus/Alertmanager, les sauvegardes et les scripts de provisionnement le lisent tous.

## Procédure

```bash
python3 tools/init-env.py            # crée .env depuis .env.example : secrets aléatoires, hash admin, mots de passe de base/Redis/RabbitMQ
$EDITOR .env                          # renseigner les lignes marquées [À RENSEIGNER]
python3 tools/check-env.py            # contrôle complet (erreurs bloquantes, avertissements) ; --strict : avertissements = erreurs
docker compose up -d --build
docker compose run --rm jasmin-provision
```

- `.env.example` est le **modèle commenté** (jamais de secret dedans : un test le vérifie). `.env` n'est jamais commité.
- `[AUTO]` = généré par `init-env.py` ; `[À RENSEIGNER]` = valeur externe (opérateur, domaine, SMTP…).
- Valeur contenant `$` (hash bcrypt) : entre apostrophes `'...'`, sinon Docker Compose l'interprète.
- Une variable déjà exportée dans l'environnement du shell l'emporte sur le fichier (utile en CI).
- `check-env.py` reprend les règles de `ProductionGuard` (démarrage refusé en production si une règle échoue) et ajoute les contrôles d'intégration : opérateur incomplet, fichier de données absent, hash incohérent.

## Où chaque point bloquant / intégration externe se règle

| Point | Variables | Source de la valeur |
|---|---|---|
| Opérateurs SMPP (TT, Orange, Ooredoo) | `{OP}_SMSC_HOST[_2..4]`, `_LINK_MODE`, `_SMSC_PORT`, `_SYSTEM_ID`, `_SMSC_PASSWORD`, `_BIND_MODE`, `_SRC/DST_TON/NPI`, `_ELINK`, `_TPS`, `_MSISDN_PREFIXES`, `_SHORT_CODES`, `_DLR_BILLING_RULE` | Contrat opérateur (Annexe B) ; un opérateur sans `_SMSC_HOST` est ignoré |
| Format des relevés de rapprochement | `{OP}_RECON_SEPARATOR`, `_ID_COLUMN`, `_AMOUNT_COLUMN`, `_STATUS_COLUMN`, `_STATUS_MAP`, `_AMOUNT_DIVISOR` | Fichier réel fourni par l'opérateur |
| Plages de numéros et portabilité | `ROUTING_RANGES_FILE`, `ROUTING_PORTED_FILE` | Plan de numérotation officiel, base de portabilité (`data/*.example.*` = modèles) |
| Catalogue (partenaires, services, mots-clés, tarifs) | `CATALOG_FILE` | Contrat client ; services créés en **brouillon**, tarifs **non approuvés** (double validation conservée) |
| TLS, domaine, accès admin | `PUBLIC_HOSTNAME`, `HTTP_PORT`, `HTTPS_PORT`, `ADMIN_ALLOWED_CIDRS`, `PROXY_API_RATE`, `PROXY_LOGIN_RATE` ; certificats dans `./certs` | DNS et certificat du client |
| Alertes | `ALERT_EMAIL_TO/FROM`, `ALERT_SMTP_HOST/USER/PASSWORD`, `ALERT_WEBHOOK_URL`, `GRAFANA_*` | SMTP / Teams / Slack du client |
| Sauvegardes | `BACKUP_DIR`, `BACKUP_GPG_RECIPIENT`, `BACKUP_RETENTION_DAYS`, `OFFSITE_TARGET` | Clé GPG **publique** du client, cible hors site |
| Conservation (RGPD / INPDP) | `RETENTION_MESSAGES_DAYS`, `RETENTION_CONSENT_DAYS`, `RETENTION_AUDIT_DAYS`, `RETENTION_WEBHOOK_DAYS` | Conseil juridique |
| Haute disponibilité | `DB_SUPERUSER_PASSWORD`, `DB_REPLICATION_PASSWORD`, `RABBIT_COOKIE` (avec `infra/ha/docker-compose.ha.yml`) | Générées |
| Lectures sur réplica (CQRS) | `READ_DB_URL`, `READ_DB_USER`, `READ_DB_PASSWORD` (vides = identifiants de la base primaire) | Optionnel |
| Réglages | `SESSION_TTL_MINUTES`, `PASSWORD_MIN_LENGTH`, `AUTH_THROTTLE_PER_MINUTE`, `MT_*`, `MO_DEDUP_SECONDS`, `DLR_TIMEOUT_HOURS`, `RATE_SAFETY_FACTOR`, `SUBSCRIPTION_*`, `*_CRON`, `DB_POOL_*`, `JAVA_*`, `LOG_LEVEL*` | Valeurs par défaut sûres |

## Fichiers de données (`./data`, montés en `/data`)

Voir `data/README.md` et les modèles `data/*.example.*`. Au démarrage, `StartupData` charge les plages, les numéros portés et le catalogue de façon **idempotente** (créer ou mettre à jour, jamais supprimer) et journalise le nombre de lignes créées, mises à jour et rejetées. Le catalogue ne contourne pas la gouvernance : services en brouillon, tarifs à approuver par un second administrateur.

## Rotation de `DATA_KEY`

Modifier `DATA_KEY` sans réécriture rend les numéros illisibles. Utiliser l'outil `tn.vas.tools.Rekey` (simulation, puis `--apply`, puis démarrage avec la nouvelle clé) : voir l’en-tête de `Rekey.java` et `tests/integration/rekey.sh`.

## Ce qui reste hors du dépôt

Contrats et VPN opérateurs, données officielles de numérotation/portabilité, certificat et domaine, serveur SMTP, clé GPG publique, avis juridique sur la conservation, test d'intrusion : seules leurs **valeurs** s'ajoutent dans `.env` ; leur obtention reste une démarche du client.
