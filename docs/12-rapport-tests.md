# Rapport de tests (exécutés sur ce dépôt)

Environnement d'exécution : conteneur de développement (JDK 21, PostgreSQL 16, Redis 7.2, Chromium). **Aucun opérateur réel, aucun Jasmin réel, pas de RabbitMQ** : le simulateur SMSC et la file mémoire remplacent ces éléments. Les chiffres de performance sont indicatifs et ne remplacent pas un test sur l'infrastructure cible.

## 1. Tests automatisés (`mvn verify`, `npm test`)

| Suite | Tests | Résultat | Contenu |
|---|---|---|---|
| `ArchitectureTests` | 6 | OK | règles CQRS (ArchUnit) : le côté requête ne dépend d'aucun service de commande, n'écrit jamais, n'expose que des GET ; le côté commande ignore les projections |
| `SsrfPinningTests` | 3 | OK | résolveur DNS qui n'accepte que des adresses publiques (connexion réelle refusée), redirections non suivies |
| `RekeyTests` | 3 | OK | rotation de DATA_KEY : simulation, réécriture, reprise, mauvaise clé |
| `ReadWriteRoutingTests` | 1 | OK | lecture seule → réplica, commande → primaire |
| `DeploymentGuardTests` | 2 | OK | image Jasmin 0.10.x imposée, alertes d'accusés manquants présentes |
| `LifecycleTests` | 16 | OK | points 10 à 15 : renouvellement (reprise J+1, suspension après 3 échecs, remise à zéro), espacement du limiteur, TPS service/partenaire, envoi programmé, **facturation** (ajustements, clôture, période figée, relevés, reversements 4 yeux, isolation portail), OAuth2, anti-SSRF, mot de passe imposé + révocation des sessions, chiffrement des numéros |
| `JasminHttpGatewayTests` | 3 | OK | classification des réponses Jasmin (412/429/5xx réessayés, 403/400 définitifs) |
| `RetentionPostgresTests` | 1 | OK sur PG réel (ignoré sans `PG_TEST_URL`) | purge du journal d'audit à travers le trigger immuable |
| `UnitTests` | 8 | OK | normalisation MSISDN, GSM-7/UCS-2 et segments, mapping DLR, répartition des revenus (somme exacte), signature webhook, **TOTP RFC 6238 (vecteur officiel)**, détection de langue, parsing CSV avec mapping |
| `SmppSimulatorTests` | 5 | OK | vrai client SMPP v3.4 (jsmpp) ↔ simulateur SMSC : bind, mauvais mot de passe rejeté, submit_sm + DLR (`DELIVRD`/`UNDELIV`), injection MO, `ESME_RTHROTTLED`, coupure de lien + reconnexion |
| `ProfileTests` | 6 | OK | `ProductionGuard` (accepte une bonne config, rejette mocks/secrets faibles/base H2/hash `{noop}`/hash admin absent), avertissements, `OperatorSync` (création, première synchro, back-office prioritaire ensuite, `overwrite`), purge de conservation (MT finaux seulement) |
| `ProfileGuardTests` + `ProfileGuardVerifyTests` | 3 | OK | refus de démarrer sans profil, refus `dev`+`pro` |
| `DevProfileTests` | 6 | OK | profil `dev` complet : données de démonstration, DLR automatiques → ledger `CHARGED`, un compte par rôle avec ses droits, clé API de démo, puits de webhook (signature + anti-rejeu), faux relevé opérateur |
| `FlowTests` | 25 | OK | MO→MT→DLR→facturation, rejeu MO/DLR idempotent, mot-clé inconnu, STOP (et arabe), double opt-in, service réglementé bloqué, lien coupé + balayeur, MO arabe/UCS-2, listes noire/blanche, MFA (enrôlement, jeton, verrouillage), portail partenaire isolé + audit, rapprochement XLSX/CSV + exports PDF/XLSX/CSV, webhooks MO/DLR signés, scopes API, RBAC, approbation 4-yeux |
| Front (`vitest`) | 13 | OK | complétude des traductions FR/AR/EN, validation accessible des formulaires, modales, tableaux, pagination, histogramme avec tableau équivalent, changement de mot de passe |

Total : **108 tests (95 Java dont 1 exécuté à part sur PostgreSQL réel + 13 front), 0 échec**.

## 2. Parcours navigateur (Chromium, `frontend/e2e/smoke.mjs`) sur PostgreSQL + Redis réels

34 vérifications OK : rôle sensible redirigé vers l'enrôlement MFA, activation TOTP, reconnexion avec code, 4 MO simulés (dont arabe), navigation dans les 17 écrans administrateur (dont Campagnes et Facturation) sans erreur, fiche service avec « Options de vote », mot de passe imposé au compte partenaire, recherche de messages, compte partenaire limité au portail (résultats par contenu), bascule arabe en RTL, aucune exception JavaScript.

## 3. Migrations et schéma
Flyway V1-V10 appliquées sur PostgreSQL 16 réel avec `ddl-auto=validate` (mapping JPA conforme au schéma).

## 4. Test de charge (`tests/load/load.mjs`, 1 instance, PostgreSQL + Redis réels, 50 connexions)

| Flux | Volume | Débit | P50 | P95 | P99 | Erreurs | Objectif CDC |
|---|---|---|---|---|---|---|---|
| MO `/callbacks/mo` (traitement complet + MT en file) | 3 000 | 152 req/s | 301 ms | 736 ms | 946 ms | 0 | P95 < 2 s |
| API `POST /api/v1/messages` (acceptation en file) | 3 000 | 186 req/s | 250 ms | 467 ms | 658 ms | 0 | P95 < 500 ms |

Débit d'envoi MT : plafonné par `operator.max_tps` (50/s) - le backlog de 4 454 MT s'est résorbé à ce rythme sans aucune perte, ce qui valide la tenue de charge en cas de throttling opérateur. Le test à 200 SMS/s agrégés avec RabbitMQ et plusieurs instances reste à exécuter sur l'infrastructure cible (script k6 fourni : `tests/load/k6-mo-mt.js`).

## 5. Sauvegarde / restauration (CDC §14.3 point 7) - exécutée sur PostgreSQL 16 réel
`backup.sh` : dump format custom chiffré GPG + SHA-256 (332 Ko pour 2 000 MO / 4 000 MT) ; `verify-restore.sh` : restauration dans une base temporaire en **1 s**, contrôle des tables clés et des migrations (5/5 réussies) ; `restore.sh` refuse la base de production sans `--force-prod`. RTO cible 2 h largement respecté sur ce volume ; à re-mesurer sur le volume de production.

## 6. Non exécuté (à faire avant mise en service)
Connexion à un SMSC opérateur réel et recette opérateur ; Jasmin réel (le gabarit `provision.sh` est validé en syntaxe et en rendu uniquement) ; RabbitMQ en cluster et bascule HA (topologie fournie, non démarrée ici faute de Docker) ; test d'intrusion ; test de charge à 200 SMS/s sur l'infrastructure cible.

## 7. Profil `pro` démarré pour de vrai (PostgreSQL 16 + Redis réels)
- Sans variables d'environnement : démarrage refusé (placeholders non résolus).
- Avec secrets faibles / mocks / `{noop}` / `localhost` : démarrage refusé par `ProductionGuard`, liste complète des erreurs.
- Avec une configuration valide : démarrage OK, opérateurs et short codes **créés depuis l'environnement** (TT : TPS 40, préfixes `9,4`, short codes 85500 et 85503 ; Orange : règle de facturation `ON_SUBMITTED`), **aucun service ni donnée de démonstration**, premier SUPER_ADMIN créé depuis un hash bcrypt, `/dev/*` et `/admin/sim/*` inaccessibles, connexion OK (MFA exigé pour les rôles sensibles).
- Non démarré : RabbitMQ réel (file `rabbit`) - l'envoi de MT en `pro` nécessite le broker.

## 8. Profil `dev` démarré et piloté dans Chromium
Bandeau « environnement de démonstration », connexion avec un compte de démonstration, tableau de bord alimenté (17 MO routés, 16 MT livrés, 1 non livré, taux de livraison 94 %), ledger de 14 événements, aucune erreur JavaScript.

## 9. Intégration réelle et base de données (lot « 19 points »)
- **Pile réelle** (PostgreSQL 16, Redis, RabbitMQ 3.12, Jasmin 0.10.13, simulateur SMSC) : `tests/integration/full-stack.mjs`, 21 vérifications, 0 échec - voir `14-tests-integration.md` (10 défauts trouvés et corrigés).
- **Base de données** : mesures sur 2 M de lignes, avant/après index - voir `15-performance-base.md`.
- **Repli Redis** : `RateLimiterTests` (Redis injoignable : service maintenu, quota local, pas d'attente).

## 10. Lot « points 6 à 15 » (moteurs, facturation, API, comptes)
- **Pile réelle** : `tests/integration/full-stack.mjs` passe à **33 vérifications, 0 échec** (OAuth2, anti-SSRF, MT programmé, facturation, mot de passe imposé, en plus du scénario MO/MT/DLR) ; `pg-audit.sh` 5/5 ; `failover.sh` 7/7 sur Jasmin 0.10.13 réel – voir `14-tests-integration.md` (13 défauts trouvés et corrigés au total).
- **Navigateur** : `smoke.mjs` – 34 vérifications OK ; `a11y.mjs` – **40 vues (fr + ar, modales ouvertes), 928 contrôles axe, 0 violation WCAG 2.1 A/AA**.
- Description fonctionnelle : `16-moteurs-facturation-comptes.md`.

## 11. Lot « points techniques » et CQRS
- **Pile réelle** : `full-stack.mjs` **39 vérifications, 0 échec** (dont tableau de bord / campagne / messages servis par le côté requête avec routage de lecture actif) ; `rekey.sh` 8/8 ; modèles de lecture égaux aux tables d'écriture sur PostgreSQL.
- Défaut observé : accusé final des messages concaténés perdu par intermittence avec Jasmin 0.10.13 + simulateur (voir `14-tests-integration.md`, défaut 14).
- Description : `17-architecture-cqrs.md` et `18-points-techniques-traites.md`.
