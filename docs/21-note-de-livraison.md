# Note de livraison — Plateforme VAS / SMS Premium SMPP v3.4 (Tunisie)

Branche `claude/loving-shannon-bwl4rl`. Ce document récapitule le contenu livré, la façon de le démarrer, la checklist de mise en production et ce qui reste à la charge du client ou des tiers.

## 1. Contenu livré
| Domaine | Contenu | Référence |
|---|---|---|
| Cœur (lots A à H) | Passerelle SMPP v3.4 (Jasmin), moteur VAS, facturation et rapprochement, API, back-office FR / AR / EN, portail partenaire, supervision, sauvegardes, documentation | `README.md`, `00` à `18` |
| Options | USSD, IVR, DCB, WhatsApp / RCS (avec modèles), serveur SMPP clients, antifraude (règles et scoring), décisionnel et entrepôt de données, PWA et applications Android / iOS | `20-options-canaux-antifraude-bi.md` |
| Configuration | Un seul fichier `.env` ; génération (`tools/init-env.py`) et contrôle (`tools/check-env.py`) ; refus de démarrer en production avec un simulateur ou un secret faible | `19-configuration-env.md` |
| SMPP v3.4 | Matrice de conformité PDU par PDU (opérateurs via Jasmin, serveur partenaires complet), champs, TLV, codes d'erreur, écarts | `22-conformite-smpp-v34.md`, `docs/conformite/Conformite_SMPP_v34_VAS_Tunisie.pdf` |
| Passage en production | Plan des 6 étapes restantes (opérateurs, partenaires SMPP, charge et HA, intrusion et juridique, tiers, stores) avec outils : `tests/recette/operator-check.mjs`, `tn.vas.tools.SmppCheck`, `tests/ha/drill.sh` | `23-plan-passage-production.md`, `docs/conformite/Plan_passage_production_VAS_Tunisie.pdf` |
| Sécurité | Revue des options et correctifs (DCB sous verrou, remise SMPP bornée, plafond de connexions, accord WhatsApp après STOP) | `07-rapport-securite.md` |
| Chiffrage | Offre commerciale (99 924 TND HT, 141 JH, 8 semaines) et achats nécessaires ; chiffrages 2 mois et compétitif | `docs/chiffrage/` |

Données : migrations Flyway `V1` à `V18` (appliquées automatiquement au démarrage).

## 2. Qualité constatée
- **146 tests automatisés, 0 échec** : 128 serveur (dont 1 exécuté à part sur PostgreSQL réel) et 18 interface (`12-rapport-tests.md`).
- **Intégration sur pile réelle** (PostgreSQL, RabbitMQ, Redis, Jasmin, simulateur SMSC) : tout est OK, y compris USSD → vote → SMS via Jasmin, décisionnel, ETL et scoring sur PostgreSQL (`tests/integration/local-stack.sh test`).
- **Navigateur** : parcours complet sans exception JavaScript ; **0 violation WCAG 2.1 A/AA** sur 48 vues (français et arabe).
- **CI** (`.github/workflows/ci.yml`) : build et tests, contrôle du `.env`, image Docker, intégration, accessibilité, parcours navigateur, APK Android, compilation iOS.

## 3. Démarrer
- **Démonstration** : `SPRING_PROFILES_ACTIVE=dev` (aucune dépendance) ; comptes, options de démonstration (USSD `*123#`, IVR `80100100`, WhatsApp, SMPP `demo` / `demo1234`) dans `13-profils-dev-pro.md`.
- **Production** : `python3 tools/init-env.py` → renseigner `.env` → `python3 tools/check-env.py` (0 erreur) → `docker compose up -d --build` → `docker compose run --rm jasmin-provision`.

## 4. Checklist de mise en production
Procédures, outils et critères d'acceptation détaillés : `23-plan-passage-production.md`.

| # | Étape | Responsable | Fait |
|---|---|---|---|
| 1 | Hébergement HA, IP, stockage hors site, domaine et certificat TLS commandés | Client | ☐ |
| 2 | VPN et comptes SMPP des 3 opérateurs, short codes attribués | Client / opérateurs | ☐ |
| 3 | `.env` renseigné, `check-env.py` sans erreur, `DATA_KEY` sauvegardée hors serveur | Prestataire + client | ☐ |
| 4 | Plages de numéros et portabilité chargées (`data/`) | Client (données) / prestataire | ☐ |
| 5 | Recette SMPP de chaque opérateur (A02 à A13 « OP »), formats d'accusés et de relevés | Prestataire + opérateurs | ☐ |
| 6 | Test de charge sur l'infrastructure cible, bascule HA vérifiée | Prestataire | ☐ |
| 7 | Test d'intrusion par un auditeur indépendant, corrections | Client (commande) / prestataire | ☐ |
| 8 | Durées de conservation et textes d'information validés par le conseil juridique | Client | ☐ |
| 9 | Premier administrateur : changement du mot de passe, MFA activé ; comptes nominatifs créés | Client | ☐ |
| 10 | Restauration de sauvegarde testée sur l'infrastructure cible (`verify-restore.sh`) | Prestataire | ☐ |
| 11 | Options : contrats et paramètres (USSD, DCB, IVR, WhatsApp / RCS, partenaires SMPP), recette A17 à A25 | Client / tiers / prestataire | ☐ |
| 12 | Applications mobiles : comptes Google Play / Apple, signature, publication | Client | ☐ |
| 13 | Formation (2 jours) et PV de transfert | Prestataire + client | ☐ |

## 5. Hors du dépôt (à obtenir par le client)
Contrats et accès des opérateurs (SMPP, USSD, DCB, numéros IVR, trunk SIP), comptes Meta WhatsApp Business et fournisseur RCS, comptes développeur Google Play et Apple, infrastructure de production, certificat, avis juridique, test d'intrusion. Détail et estimations : `Achats_necessaires_VAS_SMPP_Tunisie.pdf`.

## 6. Limites connues
- Validation contre des simulateurs (SMSC, passerelles USSD / IVR, API DCB, WhatsApp / RCS) : les formats d'API propres à chaque opérateur sont à confirmer à la signature ; les points d'adaptation sont isolés (`ChannelCallbackController`, `HttpChargingGateway`, `ChatGateways`).
- Haute disponibilité (`infra/ha`) et builds natifs non exécutés dans l'environnement de développement (pas de Docker, de SDK Android ni de Mac) : validés respectivement en préproduction et par le CI.
- La perte de `DATA_KEY` rend les numéros chiffrés irrécupérables (rotation : outil `Rekey`).
