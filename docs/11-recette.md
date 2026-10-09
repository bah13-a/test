# Matrice de recette (CDC Annexe A et §14.2)

Légende : **Auto** = test automatisé exécuté en CI (`mvn verify`) ; **SIT** = à exécuter avec le simulateur SMSC dans l'environnement de test ; **OP** = à exécuter avec l'opérateur réel (non réalisable sans contrat/VPN).

| ID | Test | Couverture | Référence |
|---|---|---|---|
| A01 | Création opérateur/connecteur sans redémarrage | Auto + SIT | API `PATCH /admin/operators`, `POST /admin/shortcodes` (config en base, audit) ; connecteur Jasmin via `provision.sh` |
| A02 | Bind SMPP | Auto (simulateur) + OP | `SmppSimulatorTests.bindSubmitAndDlr`, `wrongPasswordIsRejected` ; bind Jasmin↔opérateur : OP |
| A03 | MO keyword déclenché | Auto | `FlowTests.voteFlow_moMtDlr_idempotentBilling` |
| A04 | Keyword inconnu | Auto | `FlowTests.unknownKeywordIsSilentlyRejected` |
| A05 | MT corrélé | Auto | `voteFlow...` (correlation_id ↔ smsc_message_id, historique) |
| A06 | DLR livré + webhook | Auto | `voteFlow...`, `FlowTests.dlrWebhookIsQueuedAndSigned` |
| A07 | DLR erreur | Auto | `FlowTests.undeliveredMtIsNotCharged` |
| A08 | Doublon MO | Auto | `voteFlow...` (rejeu même `id`) |
| A09 | Throttling | Auto + charge | `SmppSimulatorTests.throttlingReturnsRthrottled` ; `RateGate` : backlog drainé à 50/s au test de charge |
| A10 | Coupure SMPP 2 min | Auto + SIT | `FlowTests.linkDown_noMessageLost_sweeperRetries`, `SmppSimulatorTests.linkDropThenRebind` ; coupure prolongée : SIT (`/drop` du simulateur) |
| A11 | Unicode arabe | Auto | `FlowTests.arabicMoGetsArabicReply` (UCS-2, réponse arabe) |
| A12 | SMS long | Auto | `UnitTests.segmentsGsmAndUnicode` |
| A13 | STOP | Auto | `FlowTests.subscriptionStopBlocksRenewal` (+ STOP arabe) |
| A14 | RBAC inter-comptes | Auto | `partnerPortalIsolation`, `apiKeyScopesAndPartnerIsolation`, `adminRbacAndFourEyesTariff` (accès refusé + audit) |
| A15 | Rapprochement | Auto | `FlowTests.reconciliationXlsxAndCsv`, `UnitTests.csvParsingWithMapping` |
| A16 | Restauration | Exécuté | `infra/backup/verify-restore.sh` (résultat dans `12-rapport-tests.md`) |

| §14.2 | Résultat attendu | Statut |
|---|---|---|
| Bind, MO, MT, DLR, Unicode, Long SMS, Throttling | voir A02-A12 | Auto avec simulateur ; **OP** à exécuter avec chaque opérateur |
| Facturation (cinématique de test opérateur) | rapprochement possible | Auto (ledger+rapprochement) ; cinématique opérateur : **OP** |
| VPN | connectivité restreinte | **OP** / infrastructure |

Conditions de réception définitive (§14.3) : les points 3 (documentation), 4 (code remis), 7 (restauration) sont couverts par ce dépôt ; les points 1-2 et 5-6 dépendent de la recette opérateur et de la remise des accès de production.
