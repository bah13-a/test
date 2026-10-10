# Tests d'intégration sur la vraie pile

`tests/integration/full-stack.mjs` pilote la chaîne complète, **sans mock côté plateforme** :

```
simulateur SMSC ──SMPP v3.4──▶ Jasmin 0.10 ──HTTP callback──▶ application (profil pro) ──▶ RabbitMQ ──▶ dispatcher
      ▲                                                                                              │
      └──────────── SMPP submit_sm ◀── Jasmin POST /send ◀───────────────────────────────────────────┘
```
Seul le SMSC opérateur est simulé (`tn.vas.smppsim.SmppSimulator`, validé par un vrai client SMPP). PostgreSQL, Redis, RabbitMQ, Jasmin et l'application sont réels.

## Lancer
```bash
tests/integration/local-stack.sh test      # démarre la pile locale puis exécute le scénario (prérequis en tête du script)
# ou, pile déjà démarrée :
BASE=http://localhost:8080 SIM=http://localhost:8081 ADMIN_USER=admin ADMIN_PASSWORD=... node tests/integration/full-stack.mjs
```
En CI (GitHub Actions, job `integration`) : `docker-compose.it.yml` démarre postgres, redis, rabbitmq, jasmin (`jookies/jasmin:0.10`) et le simulateur ; le job provisionne Jasmin avec `provision.sh`, démarre l'application et lance le script.

## Scénario (21 vérifications)
Enrôlement MFA (admin, 2 financiers) · short code synchronisé depuis la configuration · bind SMPP · MO « VOTE A » → réponse → DLR → ledger `CHARGED` · réponse française en UCS-2 (« ç » absent de GSM 03.38) · MO arabe UCS-2 → réponse arabe intacte · message de 319 caractères = 3 segments avec UDH · coupure du lien : MT accepté pendant la panne, reconnexion automatique, livré sans perte · limiteur applicatif à 3 SMS/s : aucun rejet `ESME_RTHROTTLED` · SMSC plus strict que prévu : tous les MT arrivent (Jasmin réessaie), aucun échec définitif.

## Défauts trouvés par cette pile réelle (tous corrigés)
| # | Constat sur Jasmin réel | Correction |
|---|---|---|
| 1 | `http://app:8080` refusé par `httpccm` (« url syntax is invalid ») : Jasmin exige un nom **avec point**, `localhost` ou une IP | alias réseau `app.vas.internal` dans le compose ; `provision.sh` et `ProductionGuard` refusent un hôte sans point |
| 2 | Mot de passe utilisateur Jasmin limité à **16 caractères** | `ProductionGuard` : 12 à 16 caractères `[A-Za-z0-9_-]` |
| 3 | Clés inconnues du gabarit jcli (`elink`, `rate` sur une route MO), commandes avalées quand elles s'enchaînent trop vite | `elink_interval`, retrait de `rate`, `provision.sh` attend le prompt `jcli :` entre chaque commande et échoue si jcli rejette une ligne |
| 4 | `deliversmd`, `dlrd`, `dlrlookupd` sont des **démons séparés** : sans eux, ni callbacks MO ni DLR | l'image `jookies/jasmin` les lance ; documenté pour les installations hors Docker |
| 5 | `validity-period` fait rejeter le MT (« tenths of second must be one digit ») | paramètre non envoyé ; l'expiration est gérée par l'application |
| 6 | `content` + `coding=8` part en UTF-8 brut : arabe et accents illisibles | envoi en `hex-content` (UTF-16BE ou GSM 03.38) |
| 7 | MO en UCS-2 : `content` corrompu (octets NUL) → erreur PostgreSQL ; `coding` est un **octet brut** (`%00`, `%08`) | décodage depuis `binary` (hex) + `coding` (`MoDecoder`) |
| 8 | Avec `dlr-level=3`, Jasmin envoie aussi `ESME_ROK` et `ESME_RTHROTTLED` (accusés SMSC) comme `message_status` : le statut passait à `UNKNOWN` | `DlrMapper` : `ESME_ROK` = soumis, erreurs transitoires ignorées, refus définitifs = `REJECTED` |
| 9 | Fenêtre fixe de 1 s : jusqu'à 2×TPS à cheval sur deux secondes → rejets du SMSC | `RateGate` lissé (espacement 1/TPS) avec marge `vas.rate-safety-factor` (0,9) |
| 10 | Un MT soumis dont le reçu n'est jamais corrélé restait `SUBMITTED` à vie | `DlrService.timeout` : `UNKNOWN` + facturation `DISPUTED` après `vas.dlr-timeout-hours` (72 h) ; un reçu tardif lève la contestation |

## À savoir : version de Jasmin
**Rester sur Jasmin 0.10.x** (image `jookies/jasmin:0.10`, épinglée dans le compose). Jasmin **0.11.1** a un défaut interne (`'SubmitSM' object has no attribute 'response'` dans `submit_sm_resp_event`) : la correspondance identifiant SMSC ↔ message n'est jamais enregistrée, donc aucun DLR ne remonte (vérifié avec le même scénario). Ne pas monter de version sans rejouer ce test.

## Limites
Le SMSC reste simulé : formats de DLR et comportements propres à chaque opérateur à valider lors de la recette opérateur (`11-recette.md`). Les messages en plusieurs parties peuvent ne pas recevoir de reçu corrélé selon le SMSC : le délai de DLR et le rapprochement des relevés couvrent ce cas.
