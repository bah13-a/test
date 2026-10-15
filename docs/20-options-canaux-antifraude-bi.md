# Options intégrées : USSD, IVR, DCB, WhatsApp / RCS, antifraude, décisionnel, application mobile

Toutes les options sont **développées, testées et livrées** (`ChannelsTests`, `options.test.jsx`). Celles qui dépendent d'un tiers (opérateur, Meta, fournisseur RCS) restent **désactivées** tant que leur contrat et leurs paramètres ne sont pas renseignés dans le `.env` (voir `19-configuration-env.md`, sections 14 à 16). En production, `ProductionGuard` refuse tout simulateur.

| Option | Activation | Écran | Point d'entrée |
|---|---|---|---|
| Antifraude | active par défaut (`FRAUD_*`) | Antifraude | interne (flux MO, USSD, IVR, DCB) |
| Décisionnel (BI) | toujours | Décisionnel (BI) | `GET /admin/analytics`, `/admin/analytics/export` |
| USSD | menu déclaré + passerelle USSD de l'opérateur | Canaux | `POST /callbacks/ussd` |
| IVR | menu déclaré + serveur vocal | Canaux | `POST /callbacks/ivr`, `infra/ivr/agi-bridge.py` |
| DCB | `DCB_MODE=http` + `{OP}_DCB_URL/TOKEN` | Paiements sur facture | `POST /api/v1/dcb/charges` (scope `dcb:charge`) |
| WhatsApp / RCS | `CHAT_MODE=live` + `WHATSAPP_*` / `RCS_*` | Canaux | `/hooks/whatsapp`, `/hooks/rcs` |
| Application mobile | toujours (HTTPS requis) | bouton « Installer l'application » | `manifest.webmanifest`, `sw.js` |

## Antifraude
- **Vitesse** : au-delà de `FRAUD_MO_PER_MINUTE` MO par minute (ou `FRAUD_MO_PER_HOUR` par heure) pour un même numéro, le numéro est **bloqué** `FRAUD_BLOCK_MINUTES` minutes sur tous les canaux. Les MO refusés ont l'issue `FRAUD`. **STOP et AIDE ne sont jamais bloqués** (droit de désabonnement).
- **Plafond de dépense** : `FRAUD_DAILY_SPEND_LIMIT` TND TTC par numéro et par jour, toutes actions facturées (SMS surtaxé, abonnement, USSD, IVR, DCB). L'action qui dépasserait le plafond est refusée (`LIMIT_REACHED` / 403 DCB).
- **Pics** : toutes les 10 minutes (`FRAUD_SPIKE_CRON`), un service dont les MO de la dernière heure dépassent `FRAUD_SPIKE_FACTOR` fois sa moyenne horaire des 24 h (et au moins `FRAUD_SPIKE_MIN_VOLUME`) déclenche une alerte.
- **Codes DCB** : `DCB_OTP_MAX_ATTEMPTS` codes faux bloquent le numéro (`OTP_ABUSE`).
- Alertes à **acquitter** (tracé dans l'audit), blocages **levables** à la main ; métrique `vas_fraud_alert_total{rule}` et alertes Prometheus `FraudAlerts` / `FraudSpikeBurst`.

## Décisionnel (BI)
Côté requête CQRS (lecture seule, réplica si configuré). Sur une période (≤ 366 jours) : chiffre d'affaires **facturé** (événements CHARGED) par jour, opérateur, service, partenaire et type d'événement (SMS, abonnement, USSD, IVR, DCB) avec les parts opérateur / partenaire / fournisseur et les taxes ; abonnés payants, **ARPU**, abonnements actifs, activations, résiliations, **taux d'attrition** ; volumes par canal (MO / MT par canal, sessions USSD, appels et minutes IVR, paiements DCB par statut). Exports CSV / XLSX / PDF par axe. Rôles : SUPER_ADMIN, FINANCE, VAS_MANAGER, AUDITOR.

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
- Limite WhatsApp : les réponses libres sont possibles dans la fenêtre de 24 h ouverte par le message de l'abonné ; les envois à l'initiative du partenaire exigent des modèles approuvés par Meta (non couverts).

## Application mobile (PWA)
Back-office et portail partenaire **installables** sur Android / iOS / poste (manifeste, icônes, service worker). Le service worker ne met **jamais en cache les réponses d'API** (données personnelles, jetons) : seulement l'interface et les fichiers statiques versionnés. Mise en page adaptée aux petits écrans (menu défilant, zones tactiles de 44 px). Le jeton de session reste en mémoire (aucun stockage local).

## Données et conservation
Tables : `fraud_alert`, `fraud_block`, `menu_definition`, `ussd_session`, `ivr_call`, `dcb_transaction`, `chat_channel` (migration `V13`) ; colonne `channel` sur `mo_message` / `mt_message`. Numéros chiffrés comme ailleurs (inclus dans `Rekey`). Purge de conservation : sessions USSD, appels IVR, alertes acquittées et blocages échus suivent `RETENTION_MESSAGES_DAYS` ; les paiements DCB (pièces comptables) suivent le ledger.

## Ce qui reste hors du dépôt
Codes USSD et accès aux passerelles USSD, contrats et API DCB, numéros IVR surtaxés et trunk SIP, compte WhatsApp Business vérifié (et frais de conversation Meta), agent RCS : voir le document « Achats nécessaires ». Le format exact de chaque API opérateur (USSD, DCB) est à confirmer à la signature ; les points d'adaptation sont isolés (`ChannelCallbackController`, `HttpChargingGateway`).
