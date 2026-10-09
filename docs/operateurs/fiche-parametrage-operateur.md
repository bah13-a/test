# Fiche de paramétrage opérateur (CDC §5.3 / Annexe B)

Une fiche par opérateur et par environnement (Test / Préproduction / Production). **Les secrets ne sont jamais saisis dans ce dépôt** : ils sont fournis par variables d'environnement (`TT_SMSC_HOST`, `TT_SYSTEM_ID`, `TT_SMSC_PASSWORD`, `ORANGE_...`, `OOREDOO_...`) à `infra/jasmin/provision.sh`, qui génère et applique le script `jcli` (`DRY_RUN=1` pour relire sans appliquer).

| Paramètre | Valeur à renseigner | Où ça se configure |
|---|---|---|
| Opérateur | TT / ORANGE / OOREDOO | table `operator` (code) |
| Environnement | Test / Préproduction / Production | |
| IP / FQDN SMSC | [fourni par l'opérateur] | `<OP>_SMSC_HOST` |
| Port SMPP | [fourni] | `<OP>_SMSC_PORT` |
| System ID / login | [secret] | `<OP>_SYSTEM_ID` |
| Password | [secret] | `<OP>_SMSC_PASSWORD` |
| Bind mode | TRX / TX+RX / autre | `<OP>_BIND_MODE` (`transceiver`, `transmitter`, `receiver`) |
| System type | [fourni] | `<OP>_SYSTEM_TYPE` |
| Source TON/NPI | [fourni] | `<OP>_SRC_TON`, `<OP>_SRC_NPI` |
| Destination TON/NPI | [fourni] | `<OP>_DST_TON`, `<OP>_DST_NPI` |
| Short code(s) | [attribué(s)] | Back-office → Short codes |
| TPS / débit | [contractuel] | `<OP>_TPS` (Jasmin `submit_throughput`) **et** Back-office → Opérateurs → TPS (limiteur applicatif) |
| Enquire link | [contractuel / 30 s recommandé] | `<OP>_ELINK` (secondes) |
| DLR format | [à tester et documenter] | `DlrMapper` (statuts `DELIVRD`, `UNDELIV`, `EXPIRED`, `REJECTD`, `ACCEPTD`, `ENROUTE`, `DELETED`) ; règle de facturation par opérateur |
| VPN / IPsec / whitelist | [paramètres sécurité] | infrastructure |
| Fenêtre SMPP | [à définir] | non paramétrable dans Jasmin : régulée par le débit (`submit_throughput`) ; si une fenêtre stricte est imposée, abaisser le TPS |
| Préfixes MSISDN | [source opérateur ; portabilité] | Back-office → Opérateurs → Préfixes |
| Contacts NOC | [à renseigner] | exploitation |
| Format du relevé de facturation | colonnes id / montant / statut | Back-office → Rapprochement (mapping) |
