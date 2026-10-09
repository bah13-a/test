# Conception détaillée - LLD

## 1. Découpage du code

| Paquet | Rôle |
|---|---|
| `tn.vas.gateway` | `SmsGateway` (interface), `JasminHttpGateway` (production), `SimulatorGateway` (dev/SIT) |
| `tn.vas.smppsim` | Simulateur SMSC SMPP v3.4 (jsmpp) : bind, submit_sm, DLR, MO, throttling, coupure de lien |
| `tn.vas.service` | `MoService`, `MtService`, `MtDispatcher`, `MtSweeper`, `DlrService`, `LedgerService`, `ReconciliationService`, `StatementParser`, `SubscriptionService`, `WebhookService`, `Messages` (i18n), `Text`, `RevenueSplit`, `RateGate` |
| `tn.vas.web` | `CallbackController` (Jasmin), `ApiController` (/api/v1), `AdminController` (/admin), `PortalController` (/portal), `SimulatorController`, `Exports` |
| `tn.vas.security` | `ApiKeyFilter`, `CallbackSecretFilter`, `MfaFilter`, `CorrelationFilter`, `UserService`, `Totp`, `RateLimiter` (mémoire/Redis) |
| `tn.vas.domain` / `repo` | Entités JPA, dépôts Spring Data |

## 2. Machine à états

**MT** : `PENDING` → `SUBMITTED` → {`DELIVERED`, `EXPIRED`, `UNDELIVERABLE`, `REJECTED`, `UNKNOWN`} ; `PENDING` → `FAILED` (échec définitif ou tentatives épuisées) ; `PENDING` → `EXPIRED` (validité dépassée). Un statut final n'est jamais remplacé.

**Ledger** : `PENDING` → {`ACCEPTED`, `CHARGED`, `REJECTED`} ; `CHARGED` → {`REVERSED`, `DISPUTED`} ; `DISPUTED` → {`CHARGED`, `REVERSED`} ; `REJECTED`/`REVERSED` terminaux.

**Abonnement** : (aucun) → `PENDING_CONFIRMATION` (double opt-in) → `ACTIVE` → `STOPPED` ; `STOPPED` met `next_renewal_at` à `NULL` : aucun renouvellement possible.

## 3. Algorithmes clés

**Dédoublonnage MO** : (1) `existsByOperatorAndDedupKey(id:<message_id opérateur>)` ; (2) même MSISDN + short code + contenu dans `vas.mo-dedup-seconds` ; (3) contrainte unique SQL comme filet pour les rejeux simultanés (HTTP 200 `ACK/Jasmin` sans ré-effet).

**Retry / throttling MT** : le dispatcher prend un jeton `RateGate` (fenêtre 1 s, `operator.max_tps`) ; échec retryable (réseau, 5xx, 429) → reste `PENDING`, `attempts++` ; au-delà de `vas.retry.max-attempts` → `FAILED` + ledger `REJECTED`. Le balayeur republie les `PENDING` plus anciens que `backoff-seconds`. La base reste la source de vérité : RabbitMQ n'est qu'un accélérateur de distribution.

**Répartition des revenus** (`RevenueSplit`) : HT = TTC·100/(100+TVA) ; part opérateur = HT·%op ; reste·%partenaire = part partenaire ; part fournisseur = reliquat (somme exacte du montant facial, arrondi HALF_EVEN à 3 décimales).

**Segments SMS** : GSM-7 (160 / 153 par segment, extensions comptées 2) ; UCS-2 (70 / 67). Jasmin réalise la concaténation UDH/SAR côté SMPP.

**Consentement** : `consent_record` à chaque étape (`OPT_IN_REQUEST`, `OPT_IN_CONFIRMED`, `ACTIVATED`, `STOPPED`) avec canal, texte, version des conditions. Langue des réponses : arabe si caractères arabes dans le MO, sinon langue par défaut du service ; surcharge par `service_reply`.

**Rapprochement** : lecture CSV/XLSX avec mapping de colonnes → pour chaque ligne : `MATCHED`, `AMOUNT_MISMATCH` (y compris doublon dans le relevé), `STATUS_MISMATCH`, `MISSING_ON_PLATFORM` ; événements `CHARGED` de la période absents du relevé → `MISSING_ON_OPERATOR`. Corrections journalisées (commentaire obligatoire).

## 4. Interfaces

- API partenaires : `docs/openapi.json` (Swagger UI : `/swagger-ui.html`).
- Callbacks Jasmin : `POST /callbacks/mo` (`id, from, to, content, origin-connector`), `POST /callbacks/dlr` (`cid, message_status`) ; paramètre `secret` obligatoire.
- Webhooks partenaires : corps JSON, en-têtes `X-VAS-Event-Id`, `X-VAS-Timestamp`, `X-VAS-Signature = HMAC-SHA256(secret, timestamp + "." + corps)`. Le partenaire doit rejeter les timestamps trop anciens (anti-rejeu) et dédoublonner sur `event_id`.

## 5. Schéma de données

Voir `08-dictionnaire-donnees.md`. Migrations versionnées Flyway `V1`–`V5` (jamais modifiées après livraison, uniquement de nouvelles versions).
