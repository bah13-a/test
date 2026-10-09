# Plateforme VAS / SMS Premium - SMPP v3.4 (Tunisie)

Implémentation de la V1 du *Cahier des charges technique & fonctionnel* (Tunisie Telecom, Orange Tunisie, Ooredoo Tunisie) avec la stack du CDC §4.2 / Annexe E :
**Linux + Jasmin SMS Gateway + moteur VAS Java 21 / Spring Boot + PostgreSQL + Redis + RabbitMQ + API REST (OpenAPI) + back-office React + Prometheus/Grafana/Loki**.

```
Opérateurs (SMPP v3.4, VPN) ⇄ Jasmin ⇄ callbacks HTTP ⇄ [ moteur VAS ] ⇄ PostgreSQL
                                  ▲   POST /send            │  ├─ RabbitMQ (files MT par priorité, quorum, DLQ)
                                  └─────────────────────────┘  ├─ Redis (quotas API)
                                                               └─ API /api/v1 · back-office /admin · portail /portal · /actuator/prometheus
```
La gateway ne contient aucune logique métier : l'interface `SmsGateway` la rend remplaçable (Kannel, autre).

## Deux profils : `dev` (mocks) et `pro` (données réelles)

L'application **exige un profil explicite** (sinon elle refuse de démarrer). Détail, checklist des informations externes et procédure : [`docs/13-profils-dev-pro.md`](docs/13-profils-dev-pro.md).

| | `dev` - démonstration / tests | `pro` - production |
|---|---|---|
| Lancer | `cd frontend && npm ci && npm run build && cd .. && mvn spring-boot:run -Dspring-boot.run.profiles=dev` ou `docker compose -f docker-compose.dev.yml up --build` | `cp .env.pro.example .env` (tout renseigner) → `docker compose up -d --build` → `docker compose run --rm jasmin-provision` |
| Dépendances | aucune (H2, file mémoire) | PostgreSQL, RabbitMQ, Redis, Jasmin, VPN opérateurs |
| Gateway | **mock** avec DLR automatiques, panne simulable, faux relevé opérateur, puits de webhooks signés | **Jasmin réel** vers les SMSC des opérateurs |
| Données | démonstration (opérateurs, short codes, services, tarifs, 1 compte par rôle, 24 MO simulés) | **aucune** donnée fictive ; opérateurs/short codes issus de `.env` |
| Garde-fous | bandeau « démonstration » | `ProductionGuard` : refuse mocks, secrets faibles/d'exemple, MFA désactivé, base non PostgreSQL |
| Comptes de démo | `admin / Admin-dev-pass1` ; `manager, noc, finance, finance2, support, auditor, club / Dev-pass-12345` | premier SUPER_ADMIN via `ADMIN_PASSWORD_HASH` |

Autres commandes :

| Besoin | Commande |
|---|---|
| Simulateur SMSC SMPP v3.4 (SIT) | `docker compose -f docker-compose.dev.yml --profile sit up -d smpp-sim` |
| Haute disponibilité (référence) | `docker compose -f docker-compose.yml -f infra/ha/docker-compose.ha.yml up -d` |
| Hash d'un mot de passe | `java -Dloader.main=tn.vas.tools.HashPassword -cp target/vas-platform-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher '...'` |
| Tests | `mvn verify` puis `cd frontend && npm test` (50 tests, voir `docs/12-rapport-tests.md`) |
| Parcours navigateur | `frontend/e2e/smoke.mjs` (enrôlement MFA, 14 écrans, portail, arabe RTL) |
| Charge | `node tests/load/load.mjs 3000 50` ou `tests/load/k6-mo-mt.js` |

Adresses : UI `/` · Swagger `/swagger-ui.html` (spec figée : `docs/openapi.json`) · Grafana `:3000` · Prometheus `:9090`.
Provisionnement Jasmin : `infra/jasmin/provision.sh` (variables `TT_*`, `ORANGE_*`, `OOREDOO_*` du `.env` ; `DRY_RUN=1` pour relire sans appliquer).

## Ce qui est réalisé (par lot du CDC §2.1)

| Lot | Réalisation |
|---|---|
| **A** Gateway SMPP | Jasmin (client SMPP v3.4, binds, enquire_link, throttling, store-and-forward) + adaptateur HTTP ; routage MT par opérateur ; files RabbitMQ par priorité (3), quorum, DLQ ; retry + balayeur (zéro perte) ; limiteur TPS par opérateur ; DLR idempotent ; simulateur SMSC (`tn.vas.smppsim`) validé avec un vrai client SMPP |
| **B** Moteur VAS | services, short codes, mots-clés, fenêtres de campagne, plafonds par MSISDN, listes noire/blanche, STOP/DESABO/AIDE, opt-in simple/double/API avec preuves, abonnements et renouvellements, services réglementés bloqués tant que non approuvés, **FR/AR/EN** (réponses surchargeables par service) |
| **C** Billing | ledger idempotent, tarifs versionnés non rétroactifs + approbation 4-yeux + simulateur, partage taxes/opérateur/partenaire/fournisseur, règle DLR par opérateur, **rapprochement CSV/XLSX** avec mapping de colonnes, écarts, corrections journalisées, exports CSV/XLSX/PDF |
| **D** API | `/api/v1` (messages idempotents, services, rapports, abonnements), clés hachées, scopes, quotas Redis, isolation par partenaire, webhooks **MO et DLR** signés HMAC + anti-rejeu + retry + DLQ, OpenAPI |
| **E** Back-office | React (FR/AR/EN, RTL, responsive) : tableau de bord, opérateurs, short codes, services/mots-clés/réponses, partenaires, tarifs, messages, ledger, rapprochement, listes, support/consentements, utilisateurs, clients API, audit, MFA ; 7 rôles RBAC ; exports |
| **F** Portail partenaire | rôle `PARTNER` isolé : volumes, taux de livraison, résultats par contenu, montants estimés vs rapprochés, exports |
| **G** HA / supervision | métriques Prometheus + dashboard Grafana + alertes ; logs JSON + Loki ; topologie HA de référence ; sauvegarde GPG, restauration et test de restauration scriptés **et exécutés** |
| **H** Documentation | `docs/` : HLD, LLD, guides admin/NOC/utilisateur, PRA, rapport de sécurité, dictionnaire de données, licences, formation/PV, matrice de recette, rapport de tests, fiche opérateur, OpenAPI |

Sécurité : bcrypt, verrouillage après 5 échecs, **MFA TOTP** (obligatoire SUPER_ADMIN/FINANCE), jetons de session HMAC, audit des accès refusés, MSISDN masqués, protection des exports contre l'injection de formules, CSP et en-têtes de sécurité, secrets uniquement par environnement.

## Limites connues (à lire avant mise en production)
- **Jamais testé contre un vrai SMSC opérateur ni un vrai Jasmin** : seul le simulateur SMPP est exercé. La connexion réelle, les VPN, les formats DLR/relevés propres à chaque opérateur et la recette opérateur restent à réaliser.
- La topologie HA (`infra/ha`) et le cluster RabbitMQ n'ont pas été démarrés (pas de Docker dans l'environnement de développement) : à valider en préproduction.
- Test d'intrusion et test de charge à 200 SMS/s sur l'infrastructure cible : à faire. Résultats mesurés : `docs/12-rapport-tests.md`.
- Le routage MT par préfixe n'est pas pré-rempli (portabilité des numéros) : l'opérateur vient du service/short code ou de l'appelant.
- Durées de conservation et textes d'information : à fixer avec le conseil juridique (CDC §11.3).
- Options hors V1 (USSD, DCB, IVR, RCS/WhatsApp, BI, antifraude, appli mobile, serveur SMPP) : chiffrées, non codées.

## Chiffrage
`docs/Chiffrage_detaille_VAS_SMPP_Tunisie_V1.pdf` (généré par `docs/chiffrage/generate_chiffrage.py`, hypothèses modifiables) : bordereau §19, détail par profil, planning, hébergement, options, TCO 3 ans, avancement du code et reste à faire.
