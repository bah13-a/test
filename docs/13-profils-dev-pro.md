# Profils `dev` (mocks) et `pro` (données réelles)

L'application **refuse de démarrer sans profil explicite** (`ProfileGuard`) et refuse `dev` + `pro` ensemble : on ne tombe jamais sur des mocks ou sur la configuration réelle par accident.

| | **dev** - démonstration / tests manuels | **pro** - production |
|---|---|---|
| Lancement | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` ou `docker compose -f docker-compose.dev.yml up --build` | `SPRING_PROFILES_ACTIVE=pro,json` via `docker-compose.yml` + `.env` |
| Base | H2 en mémoire (rien à installer) | PostgreSQL obligatoire (HA recommandée) |
| File MT | mémoire (asynchrone) | RabbitMQ quorum, DLQ |
| Quotas API | mémoire | Redis |
| Gateway | **mock** `SimulatorGateway` : accepte les MT, renvoie des DLR automatiques (`MIXED` : 90 % livrés, 5 % `UNDELIV`, 5 % `EXPIRED`), panne simulable | **Jasmin réel** (API HTTP) vers les SMSC opérateurs |
| Données | **démonstration** : 3 opérateurs fictifs, 5 short codes, 4 services (vote, abonnement double opt-in, quiz réglementé non approuvé, premium), tarifs approuvés, partenaire + webhook, 24 MO simulés | **aucune donnée fictive** : opérateurs/short codes issus de `.env`, le reste créé dans le back-office |
| Comptes | `admin / Admin-dev-pass1` ; `manager, noc, finance, finance2, support, auditor, club(PARTNER) / Dev-pass-12345` ; clé API `vas_dev_key_for_local_demo_only` | premier SUPER_ADMIN depuis `ADMIN_PASSWORD_HASH` ; les autres comptes créés dans l'interface |
| MFA | possible, non imposé | **obligatoire** SUPER_ADMIN / FINANCE |
| Secrets | valeurs d'exemple (publiques) | environnement uniquement, **aucune valeur par défaut** |
| Bandeau UI | « ENVIRONNEMENT DE DÉMONSTRATION » + comptes de démo sur l'écran de connexion | aucun |
| Outils mock | `/admin/sim/*` (injecter MO/DLR, panne), `/admin/sim/statement` (faux relevé opérateur avec écarts), `/dev/webhook-sink` (partenaire fictif qui vérifie la signature HMAC) | **absents** (404/403) |
| Validation au démarrage | - | `ProductionGuard` : échec avec la liste complète des problèmes |
| Conservation (purge) | désactivée | `RETENTION_*_DAYS` |

## Ce que `pro` contrôle au démarrage (`ProductionGuard`)
Échec si : simulateur actif · `JASMIN_URL` invalide · `JASMIN_PASSWORD` < 12 car. ou valeur d'exemple · `CALLBACK_SECRET` < 24 · `TOKEN_SECRET` < 32 · `DATA_KEY` < 32 ou valeur d'exemple · file ≠ `rabbit` · quotas ≠ `redis` · MFA non imposé · `VAS_PUBLIC_BASE_URL` absent ou `localhost` · base ≠ PostgreSQL · hash admin `{noop}` ou absent · mocks (`vas.mock.*`) actifs.
Avertissements (journalisés) : préfixes MSISDN vides, short code absent, aucune durée de conservation.

## Détails externes à fournir pour `pro` (checklist de mise en service)

Tout se renseigne dans `.env` (modèle : `.env.pro.example`, chaque variable est commentée).

| # | Information | D'où elle vient | Variables |
|---|---|---|---|
| 1 | Contrat de service SMS/VAS par opérateur, accord de mise à disposition | Opérateurs | - |
| 2 | Short codes attribués (INT / opérateur), période de validité | INT, opérateurs | `<OP>_SHORT_CODES` |
| 3 | SMSC : hôte, port, `system_id`, mot de passe, `system_type`, mode de bind (TRX/TX+RX), TON/NPI source/destination, enquire_link | fiche SMPP de l'opérateur (Annexe B) | `<OP>_SMSC_HOST`, `_PORT`, `_SYSTEM_ID`, `_SMSC_PASSWORD`, `_SYSTEM_TYPE`, `_BIND_MODE`, `_SRC_TON/NPI`, `_DST_TON/NPI`, `_ELINK` |
| 4 | Débit contractuel (TPS) | contrat | `<OP>_TPS` |
| 5 | Règle de facturation vs DLR (à la livraison ou à l'envoi) | contrat / cinématique de test | `<OP>_DLR_BILLING_RULE` |
| 6 | Plages de numéros (préfixes) et source de portabilité | opérateurs / ATT | `<OP>_MSISDN_PREFIXES` |
| 7 | VPN IPsec, IP publiques à déclarer, contacts NOC | opérateurs, hébergeur | hors dépôt (infrastructure) |
| 8 | Formats DLR et codes d'erreur spécifiques, jeux de tests de recette | opérateurs | `DlrMapper` (ajouter un statut si besoin) |
| 9 | Format du relevé de facturation (colonnes, séparateur, statuts) | opérateurs | mapping dans l'écran Rapprochement |
| 10 | Grille tarifaire (prix facial, % opérateur, taxes) par service | contrat / finance | écran Tarifs (création + approbation 4 yeux) |
| 11 | Partenaires : % de partage, URL et secret de webhook | contrats partenaires | écran Partenaires |
| 12 | Durées de conservation (messages, consentements, audit) et texte d'information | **conseil juridique** | `RETENTION_*_DAYS` |
| 13 | Services réglementés (jeux, concours, etc.) : autorisation obtenue | juridique / INT | bouton « Approbation réglementaire » (SUPER_ADMIN) |
| 14 | Nom DNS public + certificat TLS | hébergeur / client | `PUBLIC_HOSTNAME`, `certs/fullchain.pem`, `certs/privkey.pem` |
| 15 | Destinataires des alertes (e-mail, Teams/Slack), serveur SMTP | exploitation | `ALERT_*` |
| 16 | Clé publique GPG pour les sauvegardes, destination hors site | client | `BACKUP_GPG_RECIPIENT`, `OFFSITE_TARGET` |
| 17 | Mots de passe : PostgreSQL, RabbitMQ, Redis, Jasmin, jcli, Grafana ; secrets applicatifs | générés par le client (`openssl rand -base64 36`) | `DB_PASSWORD`, `RABBIT_PASSWORD`, `REDIS_PASSWORD`, `JASMIN_PASSWORD`, `JCLI_PASSWORD`, `GRAFANA_PASSWORD`, `TOKEN_SECRET`, `CALLBACK_SECRET`, **`DATA_KEY`** (chiffrement des numéros : à sauvegarder hors serveur, voir 16) |

## Procédure de mise en service `pro`

```bash
cp .env.pro.example .env                    # renseigner TOUT (voir checklist) - fichier jamais commité
mkdir -p certs secrets                      # certs/fullchain.pem, certs/privkey.pem ; secrets/prometheus_password
# Hash du mot de passe administrateur (aucun outil externe) :
java -Dloader.main=tn.vas.tools.HashPassword -cp target/vas-platform-1.0.0.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher 'MotDePasse-Fort-123'   # -> {bcrypt}$2a$... à coller dans ADMIN_PASSWORD_HASH
docker compose up -d --build                # postgres, redis, rabbitmq, jasmin, app (ProductionGuard), proxy TLS, supervision
docker compose run --rm jasmin-provision    # connecteurs SMPP, utilisateurs et routes Jasmin depuis .env (DRY_RUN=1 pour relire)
```
Puis : se connecter, **activer le MFA** (obligatoire), vérifier Opérateurs/Short codes (synchronisés depuis `.env`), créer partenaires/services/tarifs, exécuter la recette opérateur (`11-recette.md`) **avant** d'activer les services.

Comportement de la synchronisation `vas.operators` : au premier démarrage, la configuration s'applique à chaque opérateur ; ensuite le back-office fait foi (`OPERATORS_SYNC=create-only`). `OPERATORS_SYNC=overwrite` force la configuration à chaque démarrage (déploiements pilotés par le code).

## Ajouter un opérateur ou un paramètre
1. `application-pro.yml` : un bloc `vas.operators[]` + variables dans `.env.pro.example`.
2. `infra/jasmin/provision.sh` : ajouter son préfixe de variables dans la boucle.
3. Aucun changement de code métier : le routage MT/MO est piloté par les données (opérateur, short code, mot-clé).
