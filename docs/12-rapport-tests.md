# Rapport de tests (exécutés sur ce dépôt)

Environnement d'exécution : conteneur de développement (JDK 21, PostgreSQL 16, Redis 7.2, Chromium). **Aucun opérateur réel, aucun Jasmin réel, pas de RabbitMQ** : le simulateur SMSC et la file mémoire remplacent ces éléments. Les chiffres de performance sont indicatifs et ne remplacent pas un test sur l'infrastructure cible.

## 1. Tests automatisés (`mvn verify`, `npm test`)

| Suite | Tests | Résultat | Contenu |
|---|---|---|---|
| `UnitTests` | 8 | OK | normalisation MSISDN, GSM-7/UCS-2 et segments, mapping DLR, répartition des revenus (somme exacte), signature webhook, **TOTP RFC 6238 (vecteur officiel)**, détection de langue, parsing CSV avec mapping |
| `SmppSimulatorTests` | 5 | OK | vrai client SMPP v3.4 (jsmpp) ↔ simulateur SMSC : bind, mauvais mot de passe rejeté, submit_sm + DLR (`DELIVRD`/`UNDELIV`), injection MO, `ESME_RTHROTTLED`, coupure de lien + reconnexion |
| `FlowTests` | 19 | OK | MO→MT→DLR→facturation, rejeu MO/DLR idempotent, mot-clé inconnu, STOP (et arabe), double opt-in, service réglementé bloqué, lien coupé + balayeur, MO arabe/UCS-2, listes noire/blanche, MFA (enrôlement, jeton, verrouillage), portail partenaire isolé + audit, rapprochement XLSX/CSV + exports PDF/XLSX/CSV, webhooks MO/DLR signés, scopes API, RBAC, approbation 4-yeux |
| Front (`vitest`) | 3 | OK | complétude des traductions FR/AR/EN, bascule de langue |

Total : **35 tests, 0 échec**.

## 2. Parcours navigateur (Chromium, `frontend/e2e/smoke.mjs`) sur PostgreSQL + Redis réels

26 vérifications OK : rôle sensible redirigé vers l'enrôlement MFA, activation TOTP, reconnexion avec code, 4 MO simulés (dont arabe), navigation dans les 14 écrans administrateur sans erreur, recherche de messages, compte partenaire limité au portail (résultats par contenu), bascule arabe en RTL, aucune exception JavaScript.

## 3. Migrations et schéma
Flyway V1-V5 appliquées sur PostgreSQL 16 réel avec `ddl-auto=validate` (mapping JPA conforme au schéma).

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
