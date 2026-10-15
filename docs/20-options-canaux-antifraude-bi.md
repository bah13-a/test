# Options intégrées : USSD, IVR, DCB, WhatsApp / RCS, serveur SMPP, antifraude, décisionnel et entrepôt, applications mobiles

Toutes les options sont **développées, testées et livrées** (`ChannelsTests`, `options.test.jsx`). Celles qui dépendent d'un tiers (opérateur, Meta, fournisseur RCS) restent **désactivées** tant que leur contrat et leurs paramètres ne sont pas renseignés dans le `.env` (voir `19-configuration-env.md`, sections 14 à 16). En production, `ProductionGuard` refuse tout simulateur.

| Option | Activation | Écran | Point d'entrée |
|---|---|---|---|
| Antifraude | active par défaut (`FRAUD_*`) | Antifraude | interne (flux MO, USSD, IVR, DCB) |
| Décisionnel (BI) | toujours | Décisionnel (BI) | `GET /admin/analytics`, `/admin/analytics/export` |
| USSD | menu déclaré + passerelle USSD de l'opérateur | Canaux | `POST /callbacks/ussd` |
| IVR | menu déclaré + serveur vocal | Canaux | `POST /callbacks/ivr`, `infra/ivr/agi-bridge.py` |
| DCB | `DCB_MODE=http` + `{OP}_DCB_URL/TOKEN` | Paiements sur facture | `POST /api/v1/dcb/charges` (scope `dcb:charge`) |
| WhatsApp / RCS | `CHAT_MODE=live` + `WHATSAPP_*` / `RCS_*` | Canaux | `/hooks/whatsapp`, `/hooks/rcs` |
| Application mobile (PWA) | toujours (HTTPS requis) | bouton « Installer l'application » | `manifest.webmanifest`, `sw.js` |
| Applications natives Android / iOS | comptes développeur Google Play / Apple au nom du client | - | `mobile/` (Capacitor), builds en CI |
| Serveur SMPP clients | `SMPP_SERVER_ENABLED=true` + accès par client | Canaux → Accès SMPP | port `SMPP_SERVER_PORT` (VPN partenaires) |
| Modèles WhatsApp | `CHAT_MODE=live` + modèles approuvés chez Meta | Canaux → Modèles WhatsApp | `POST /api/v1/chat/optins`, `/api/v1/chat/messages` (scope `chat:send`) |
| Scoring antifraude | avec l'antifraude (`FRAUD_SCORE_*`) | Antifraude → Scores de risque | interne (toutes les 5 min) |
| Entrepôt de données | toujours (`DWH_ETL_*`) | Décisionnel → Entrepôt | tables `dw_*`, `/admin/analytics/dwh/export` |

## Antifraude
- **Vitesse** : au-delà de `FRAUD_MO_PER_MINUTE` MO par minute (ou `FRAUD_MO_PER_HOUR` par heure) pour un même numéro, le numéro est **bloqué** `FRAUD_BLOCK_MINUTES` minutes sur tous les canaux. Les MO refusés ont l'issue `FRAUD`. **STOP et AIDE ne sont jamais bloqués** (droit de désabonnement).
- **Plafond de dépense** : `FRAUD_DAILY_SPEND_LIMIT` TND TTC par numéro et par jour, toutes actions facturées (SMS surtaxé, abonnement, USSD, IVR, DCB). L'action qui dépasserait le plafond est refusée (`LIMIT_REACHED` / 403 DCB).
- **Pics** : toutes les 10 minutes (`FRAUD_SPIKE_CRON`), un service dont les MO de la dernière heure dépassent `FRAUD_SPIKE_FACTOR` fois sa moyenne horaire des 24 h (et au moins `FRAUD_SPIKE_MIN_VOLUME`) déclenche une alerte.
- **Codes DCB** : `DCB_OTP_MAX_ATTEMPTS` codes faux bloquent le numéro (`OTP_ABUSE`).
- **Scoring statistique** (toutes les 5 minutes, `FRAUD_SCORE_CRON`) : chaque numéro actif dans l'heure reçoit un score de 0 à 100, **expliqué** par ses points : volume (écart à la population active en écarts-types), dispersion sur plusieurs services, part du plafond de dépense consommée, sondage (part de MO refusés), activité nocturne, échecs de paiement DCB, nouveauté du numéro (aggravante seulement). Score ≥ `FRAUD_SCORE_ALERT` (60) : alerte ; ≥ `FRAUD_SCORE_BLOCK` (85) : blocage temporaire. Les **pics par service** combinent rapport à la moyenne et écart-type horaire (`FRAUD_SPIKE_Z`). Seuils à calibrer sur l'historique réel (distribution visible dans l'écran).
- Alertes à **acquitter** (tracé dans l'audit), blocages **levables** à la main ; métrique `vas_fraud_alert_total{rule}` et alertes Prometheus `FraudAlerts` / `FraudSpikeBurst`.

## Décisionnel (BI)
Côté requête CQRS (lecture seule, réplica si configuré). Sur une période (≤ 366 jours) : chiffre d'affaires **facturé** (événements CHARGED) par jour, opérateur, service, partenaire et type d'événement (SMS, abonnement, USSD, IVR, DCB) avec les parts opérateur / partenaire / fournisseur et les taxes ; abonnés payants, **ARPU**, abonnements actifs, activations, résiliations, **taux d'attrition** ; volumes par canal (MO / MT par canal, sessions USSD, appels et minutes IVR, paiements DCB par statut). Exports CSV / XLSX / PDF par axe. Rôles : SUPER_ADMIN, FINANCE, VAS_MANAGER, AUDITOR.

### Entrepôt de données
Schéma en étoile au grain du jour : `dw_service` (dimension), `dw_fact_billing` (facturation par service, opérateur, type et statut), `dw_fact_traffic` (MO / MT par canal et état), `dw_fact_subscription` (activations, résiliations), `dw_etl_run` (journal). **ETL nocturne** (`DWH_ETL_CRON`) : recharge les `DWH_ETL_DAYS` derniers jours (accusés et rapprochements tardifs), tout l'historique au premier lancement ; idempotent (relancer donne le même résultat) ; relance manuelle d'une période dans l'écran Décisionnel. Les tableaux de bord lisent l'entrepôt pour les périodes closes qu'il couvre (affiché « Source des montants »), sinon les tables de production.
Outil décisionnel externe (Power BI, Metabase, Superset) : extraction CSV / XLSX des tables de faits, ou connexion directe avec un rôle en lecture seule, par exemple :
```sql
CREATE ROLE vas_bi LOGIN PASSWORD '...';
GRANT SELECT ON dw_service, dw_fact_billing, dw_fact_traffic, dw_fact_subscription, dw_etl_run TO vas_bi;
```

## Menus USSD et IVR
Un menu est un arbre JSON rattaché à un service et à un code (USSD `*123#`) ou un numéro appelé (IVR) :
```json
{"text": "Bienvenue\n1. Voter\n2. Info", "options": {
  "1": {"text": "1. Candidat A\n2. Candidat B", "options": {
        "1": {"text": "Vote A enregistré", "action": "KEYWORD", "param": "VOTE A"},
        "2": {"text": "Vote B enregistré", "action": "KEYWORD", "param": "VOTE B"}}},
  "2": {"text": "Info du jour", "action": "CHARGE"}}}
```
- `END` (défaut) : affiche / prononce le texte et termine.
- `KEYWORD` : envoie `param` comme un MO au short code du service : **vote, quiz, abonnement, contenu premium, STOP** réutilisent les moteurs SMS, avec leurs règles (consentement, listes, limites, antifraude) ; la confirmation part par **SMS** (facturée comme le SMS).
- `CHARGE` : facture une fois le tarif **USSD** ou **IVR** approuvé du service (double validation des tarifs conservée).
- Validation à l'enregistrement (touches 0-9 et #, profondeur ≤ 8, textes USSD ≤ 182 caractères, `KEYWORD` avec `param`) ; aperçu sans effet dans l'écran Canaux.

### Passerelle USSD (opérateur)
`POST /callbacks/ussd` (réseau privé / VPN, paramètre `secret` = `CALLBACK_SECRET`) avec `sessionId`, `phoneNumber` (ou `msisdn`), `serviceCode`, et soit `text` (saisies cumulées « 1*2 »), soit `input` (dernière saisie). Réponse `text/plain` : `CON <texte>` (attendre une saisie) ou `END <texte>`. Une session terminée ne rejoue rien (idempotence MO et facturation). Si la passerelle de l'opérateur utilise un autre format (SMPP USSD, XML), seul ce contrôleur est à adapter.

### Serveur vocal (IVR)
`POST /callbacks/ivr` avec `callId`, `msisdn`, `number` (numéro appelé), `event` = `start` | `input` | `hangup`, `digits`, `duration`. Réponse JSON `{action: prompt | hangup, text, lang, maxDigits}`. **Facturation à la minute entamée** au raccroché (tarif IVR × minutes, une seule fois même si le raccroché est rejoué), en plus des feuilles `CHARGE`.
Asterisk : `infra/ivr/agi-bridge.py` (AGI) relie un plan de numérotation à la plateforme ; synthèse vocale `espeak-ng` mise en cache, ou fichiers studio avec un texte `sound:nom`. Trois silences consécutifs terminent l'appel.

## Paiement sur facture opérateur (DCB)
1. `POST /api/v1/dcb/charges` `{msisdn, serviceId, clientRef?, description?}` : le **montant est le tarif DCB approuvé** du service (jamais un montant libre du partenaire) ; contrôles antifraude ; un **code à 6 chiffres** est envoyé par SMS (valable `DCB_OTP_TTL_SECONDS`). Idempotent par `clientRef`.
2. `POST /api/v1/dcb/charges/{id}/confirm` `{code}` : code haché, essais limités ; appel de l'API de facturation de l'opérateur ; événement `DCB` CHARGED au ledger (répartition opérateur / partenaire / fournisseur comme tout tarif).
3. `POST .../{id}/refund` (partenaire) ou bouton Rembourser (finance) : remboursement opérateur puis événement REVERSED.
Statuts : `OTP_SENT → CHARGED | FAILED | EXPIRED`, `CHARGED → REFUNDED`. Une transaction n'est visible que du client API qui l'a créée.
Adaptateur `HttpChargingGateway` : `POST {OP}_DCB_URL/charge` et `/refund`, JSON `{transactionId, msisdn, amount, currency, description}`, jeton Bearer ; réponse `{"status":"SUCCESS","reference":"..."}`. **Chaque opérateur ayant son API, cet adaptateur est l'unique point à ajuster** à la signature du contrat DCB (lot 13).

## WhatsApp et RCS
- Un compte WhatsApp (`phone_number_id`) ou un agent RCS est **rattaché à un short code** (écran Canaux) : ses mots-clés, services et règles s'appliquent ; STOP / AIDE fonctionnent ; la réponse repart sur le **même canal**, jamais sur la facture opérateur (pas de surtaxe : monétiser par DCB ou abonnement SMS).
- Webhooks publics **signés** : `X-Hub-Signature-256` (secret d'application Meta `WHATSAPP_APP_SECRET`) et `X-Signature` HMAC-SHA256 (`RCS_WEBHOOK_SECRET`) ; signature absente ou fausse → 401 tracé. Vérification d'abonnement Meta par `WHATSAPP_VERIFY_TOKEN` (généré par `init-env.py`).
- Statuts de remise (delivered / read / failed) → cycle de vie du MT comme un accusé SMS.
- Réponses libres dans la fenêtre de 24 h ouverte par le message de l'abonné ; **hors fenêtre, envois par modèle** (ci-dessous).

### Modèles WhatsApp (envois à l'initiative du partenaire)
1. Créer et faire approuver le modèle chez Meta (gestionnaire WhatsApp), puis le **déclarer** dans l'écran Canaux avec le même nom et la même langue (corps avec `{{1}}`… : le nombre de paramètres est déduit).
2. Une **autre personne** que l'auteur constate l'approbation (double validation) ; un modèle suspendu par Meta se **retire** d'un clic.
3. Le partenaire enregistre l'**accord WhatsApp** de l'abonné (`POST /api/v1/chat/optins`, preuve obligatoire) puis envoie (`POST /api/v1/chat/messages` : `to`, `serviceId`, `template`, `lang`, `params`, `clientRef` idempotent).
4. Refus si : modèle non approuvé, mauvais nombre de paramètres, pas d'accord WhatsApp, STOP ultérieur (tous canaux, y compris STOP reçu sur WhatsApp), liste noire, antifraude.

## Serveur SMPP pour clients externes
Les partenaires et agrégateurs peuvent se connecter **en SMPP v3.4** à la plateforme (en plus de l'API REST) :
- **Accès** attribué par client API dans Canaux → Accès SMPP : `system_id`, mot de passe de 8 caractères **généré et affiché une seule fois** (stocké haché), adresses IP autorisées, nombre maximal de connexions, réception des MO. Changer ou retirer l'accès ferme les connexions ouvertes ; la révocation du client aussi. Échecs de connexion freinés par adresse et tracés dans l'audit.
- **bind** TX / RX / TRX ; `submit_sm` soumis exactement aux règles de l'API : expéditeur = short code d'un service actif du client, listes noire / blanche, antifraude, quota par minute (`ESME_RTHROTTLED`), facturation au tarif approuvé, puis acheminement vers l'opérateur par Jasmin. Codes d'erreur : `ESME_RINVSRCADR`, `ESME_RINVDSTADR`, `ESME_RSUBMITFAIL`, `ESME_RINVMSGLEN`.
- Messages longs : **UDH réassemblés** (références 8 et 16 bits, 6 parties maximum, partie manquante abandonnée après 2 minutes) ou `message_payload` ; GSM-7, Latin-1 et UCS-2 (arabe).
- **Accusés** (`registered_delivery`) en `deliver_sm`, un par partie, **rejoués à la reconnexion** si le client était déconnecté ; **MO** des services du client remis en `deliver_sm` (option).
- Exploitation : port `SMPP_SERVER_PORT` publié **uniquement** sur l'interface du VPN partenaires (`SMPP_SERVER_BIND`).
- Non pris en charge (refus explicite) : `submit_multi`, `query_sm`, `replace_sm`, `cancel_sm`, `data_sm`.

## Applications natives Android / iOS
Dossier `mobile/` (Capacitor 8) : applications natives qui chargent l'interface depuis `https://PUBLIC_HOSTNAME` (même origine que l'API : aucun CORS, mises à jour sans republier). `npm run sync` génère la configuration depuis le `.env` (`PUBLIC_HOSTNAME`, `MOBILE_APP_ID`, `MOBILE_APP_NAME`) ; écran d'erreur hors connexion inclus ; icônes aux couleurs de la plateforme.
- **Android** : `npm run android:apk` (APK de test) et `npm run android:bundle` (AAB pour Google Play, à signer avec la clé du client). Le CI construit l'APK à chaque push (artefact `vas-android-debug`).
- **iOS** : projet Xcode (`mobile/ios`, Swift Package Manager) ; le CI le compile pour le simulateur ; la signature et la publication se font sur un Mac avec le compte Apple Developer du client.
- Publication : comptes développeur Google Play et Apple **au nom du client** (achats nécessaires) ; Apple peut refuser une application qui ne fait qu'afficher un site (règle 4.2) : prévoir à la soumission la description des fonctions métier (back-office, portail, notifications à venir).

## Application mobile (PWA)
Back-office et portail partenaire **installables** sur Android / iOS / poste (manifeste, icônes, service worker). Le service worker ne met **jamais en cache les réponses d'API** (données personnelles, jetons) : seulement l'interface et les fichiers statiques versionnés. Mise en page adaptée aux petits écrans (menu défilant, zones tactiles de 44 px). Le jeton de session reste en mémoire (aucun stockage local).

## Données et conservation
Tables : `fraud_alert`, `fraud_block`, `menu_definition`, `ussd_session`, `ivr_call`, `dcb_transaction`, `chat_channel` (migration `V13`), accès SMPP (`V14`), `chat_template` (`V15`), `fraud_score` (`V16`), entrepôt `dw_*` (`V17`) ; colonnes `channel`, `payload`, `smpp_dlr` sur les messages. Numéros chiffrés comme ailleurs (inclus dans `Rekey`). Purge de conservation : sessions USSD, appels IVR, alertes acquittées et blocages échus suivent `RETENTION_MESSAGES_DAYS` ; les paiements DCB (pièces comptables) suivent le ledger.

## Ce qui reste hors du dépôt
Codes USSD et accès aux passerelles USSD, contrats et API DCB, numéros IVR surtaxés et trunk SIP, compte WhatsApp Business vérifié (et frais de conversation Meta), agent RCS, comptes développeur Google Play et Apple : voir le document « Achats nécessaires ». Les builds natifs n'ont pas pu être exécutés dans l'environnement de développement (pas de SDK Android ni de Mac) : ils sont confiés au CI. Le format exact de chaque API opérateur (USSD, DCB) est à confirmer à la signature ; les points d'adaptation sont isolés (`ChannelCallbackController`, `HttpChargingGateway`).
