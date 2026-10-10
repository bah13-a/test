# Dictionnaire de données

Généré depuis le schéma PostgreSQL réel après application des migrations Flyway V1–V12 (`src/main/resources/db/migration` et `db/vendor/postgresql`) par `tools/gen_dictionary.py`.
Montants : `DECIMAL(12,3)` (millimes de dinar) ; totaux de période et reversements : `DECIMAL(14,3)`. Horodatages : `TIMESTAMP WITH TIME ZONE` (UTC).
Les numéros de téléphone (`msisdn`) sont stockés **chiffrés** (`enc:v1:…`, AES-256 déterministe, clé `vas.data-key`) : colonnes `VARCHAR(100)`.


## `api_client`

Clients API : hash SHA-256 de la clé, scopes, quota par minute.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `name` | character varying(120) | non |  |
| `key_hash` | character varying(64) | non |  |
| `scopes` | character varying(200) | non |  |
| `partner_id` | bigint | oui | → `partner` |
| `rate_limit_per_min` | integer | non | 600 |
| `active` | boolean | non | true |

## `app_user`

Comptes back-office : hash bcrypt, rôles, TOTP, verrouillage.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `username` | character varying(80) | non |  |
| `password_hash` | character varying(120) | non |  |
| `roles` | character varying(200) | non |  |
| `partner_id` | bigint | oui | → `partner` |
| `totp_secret` | character varying(64) | oui |  |
| `mfa_enabled` | boolean | non | false |
| `active` | boolean | non | true |
| `failed_logins` | integer | non | 0 |
| `locked_until` | timestamp with time zone | oui |  |
| `must_change_password` | boolean | non | false |
| `token_version` | integer | non | 0 |

## `audit_log`

Journal d'audit applicatif (aucune interface de modification).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `actor` | character varying(80) | non |  |
| `action` | character varying(60) | non |  |
| `target` | character varying(120) | oui |  |
| `detail` | character varying(1000) | oui |  |
| `at` | timestamp with time zone | non |  |

## `billing_period`

Périodes de facturation clôturées (totaux figés ; plus aucune transition de ledger dans ces dates).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `from_at` | timestamp with time zone | non |  |
| `to_at` | timestamp with time zone | non |  |
| `closed_at` | timestamp with time zone | non |  |
| `closed_by` | character varying(80) | non |  |
| `events` | integer | non |  |
| `gross` | numeric | non |  |
| `operator_share` | numeric | non |  |
| `partner_share` | numeric | non |  |
| `provider_share` | numeric | non |  |
| `taxes` | numeric | non |  |

## `consent_record`

Preuves de consentement historisées (source, canal, texte, version des conditions).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `msisdn` | character varying(100) | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `action` | character varying(20) | non |  |
| `channel` | character varying(20) | non |  |
| `proof_text` | character varying(1000) | oui |  |
| `terms_version` | character varying(20) | oui |  |
| `at` | timestamp with time zone | non |  |

## `content_item`

Contenus premium diffusables par lien (code, titre, texte/URL, nombre d'usages et durée du lien).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `code` | character varying(40) | non |  |
| `title` | character varying(120) | non |  |
| `body` | character varying(2000) | oui |  |
| `url` | character varying(400) | oui |  |
| `max_uses` | integer | non | 1 |
| `ttl_hours` | integer | non | 24 |

## `content_token`

Jetons de lien à usage limité émis par numéro (hash, expiration, compteur d'usages).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `token` | character varying(64) | non |  |
| `item_id` | bigint | non | → `content_item` |
| `msisdn` | character varying(100) | non |  |
| `created_at` | timestamp with time zone | non |  |
| `expires_at` | timestamp with time zone | non |  |
| `uses` | integer | non | 0 |

## `keyword`

Mot-clé routant un MO vers un service (par short code).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `word` | character varying(40) | non |  |

## `ledger_event`

Ledger d'événements facturables, identifiant unique idempotent, répartition opérateur/fournisseur/partenaire/taxes.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `event_id` | character varying(100) | non |  |
| `event_type` | character varying(20) | non |  |
| `operator_id` | bigint | non | → `operator` |
| `service_id` | bigint | oui | → `vas_service` |
| `short_code` | character varying(20) | oui |  |
| `msisdn` | character varying(100) | non |  |
| `gross_amount` | numeric | non |  |
| `operator_share` | numeric | non |  |
| `provider_share` | numeric | non |  |
| `partner_share` | numeric | non |  |
| `taxes` | numeric | non |  |
| `billing_status` | character varying(20) | non |  |
| `source_reference` | character varying(100) | oui |  |
| `created_at` | timestamp with time zone | non |  |
| `updated_at` | timestamp with time zone | non |  |

## `mo_message`

SMS entrants (MO) avec clé de dédoublonnage unique par opérateur et issue du traitement.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `operator_id` | bigint | non | → `operator` |
| `dedup_key` | character varying(120) | non |  |
| `operator_msg_id` | character varying(80) | oui |  |
| `msisdn` | character varying(100) | non |  |
| `short_code` | character varying(20) | non |  |
| `content` | character varying(1000) | non |  |
| `received_at` | timestamp with time zone | non |  |
| `service_id` | bigint | oui | → `vas_service` |
| `outcome` | character varying(30) | non |  |

## `msisdn_rule`

Listes noire/blanche de MSISDN (globales ou par service).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `msisdn` | character varying(100) | non |  |
| `service_id` | bigint | oui | → `vas_service` |
| `rule_type` | character varying(10) | non |  |
| `reason` | character varying(200) | oui |  |
| `created_at` | timestamp with time zone | non |  |

## `mt_message`

SMS sortants (MT) : statut normalisé + brut, corrélation avec l'ID SMSC, tentatives, facturabilité.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `correlation_id` | character varying(60) | non |  |
| `client_ref` | character varying(80) | oui |  |
| `operator_id` | bigint | non | → `operator` |
| `service_id` | bigint | oui | → `vas_service` |
| `msisdn` | character varying(100) | non |  |
| `sender` | character varying(20) | non |  |
| `content` | character varying(1000) | non |  |
| `encoding` | character varying(10) | non |  |
| `segments` | integer | non |  |
| `priority` | character varying(15) | non |  |
| `status` | character varying(20) | non |  |
| `raw_status` | character varying(40) | oui |  |
| `smsc_message_id` | character varying(80) | oui |  |
| `attempts` | integer | non | 0 |
| `billable` | boolean | non | false |
| `mo_id` | bigint | oui |  |
| `api_client_id` | bigint | oui |  |
| `created_at` | timestamp with time zone | non |  |
| `updated_at` | timestamp with time zone | non |  |
| `validity_until` | timestamp with time zone | oui |  |
| `subscription_id` | bigint | oui |  |
| `scheduled_at` | timestamp with time zone | oui |  |

## `mt_status_history`

Historique horodaté de chaque transition de statut d'un MT.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `mt_id` | bigint | non | → `mt_message` |
| `status` | character varying(20) | non |  |
| `raw_status` | character varying(40) | oui |  |
| `at` | timestamp with time zone | non |  |

## `number_range`

Plages de numéros (préfixe national) attribuées à un opérateur ; plus long préfixe gagnant. Chargées par import CSV.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `prefix` | character varying(12) | non |  |
| `operator_id` | bigint | non | → `operator` |
| `source` | character varying(60) | oui |  |
| `updated_at` | timestamp with time zone | non |  |

## `operator`

Opérateur mobile et paramètres métier de routage (les secrets SMPP vivent uniquement dans Jasmin).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `code` | character varying(20) | non |  |
| `name` | character varying(80) | non |  |
| `msisdn_prefixes` | character varying(200) | non |  |
| `jasmin_connector` | character varying(80) | non |  |
| `dlr_billing_rule` | character varying(20) | non | 'ON_DELIVERED'::character varying |
| `max_tps` | integer | non | 50 |
| `status` | character varying(20) | non | 'ACTIVE'::character varying |
| `config_applied` | boolean | non | false |

## `partner`

Partenaire média/club/client : part de revenus et webhook (secret HMAC).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `name` | character varying(120) | non |  |
| `share_percent` | numeric | non | 0 |
| `webhook_url` | character varying(400) | oui |  |
| `webhook_secret` | character varying(120) | oui |  |
| `max_tps` | integer | non | 0 |

## `partner_payout`

Reversements aux partenaires (PENDING → PAID, créateur ≠ payeur, aucun chevauchement de période).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `partner_id` | bigint | non | → `partner` |
| `from_at` | timestamp with time zone | non |  |
| `to_at` | timestamp with time zone | non |  |
| `amount` | numeric | non |  |
| `status` | character varying(10) | non |  |
| `created_at` | timestamp with time zone | non |  |
| `created_by` | character varying(80) | non |  |
| `paid_at` | timestamp with time zone | oui |  |
| `paid_by` | character varying(80) | oui |  |
| `reference` | character varying(100) | oui |  |

## `ported_number`

Numéros portés : exception exacte prioritaire sur les plages (numéro chiffré).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `msisdn` | character varying(100) | non |  |
| `operator_id` | bigint | non | → `operator` |
| `source` | character varying(60) | oui |  |
| `updated_at` | timestamp with time zone | non |  |

## `quiz_progress`

Progression d'un participant (question courante, score, terminé) : une partie par numéro et par service.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `msisdn` | character varying(100) | non |  |
| `current_position` | integer | non |  |
| `score` | integer | non | 0 |
| `status` | character varying(12) | non |  |
| `started_at` | timestamp with time zone | non |  |
| `completed_at` | timestamp with time zone | oui |  |

## `quiz_question`

Questions de quiz par service (rang, réponses acceptées séparées par « | », points, réponses).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `position` | integer | non |  |
| `question` | character varying(500) | non |  |
| `answers` | character varying(300) | non |  |
| `points` | integer | non | 1 |
| `reply_correct` | character varying(300) | oui |  |
| `reply_wrong` | character varying(300) | oui |  |

## `recon_item`

Lignes de rapprochement opérateur et écarts.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `batch_id` | character varying(40) | non |  |
| `operator_id` | bigint | non | → `operator` |
| `event_id` | character varying(100) | oui |  |
| `operator_amount` | numeric | oui |  |
| `platform_amount` | numeric | oui |  |
| `result` | character varying(30) | non |  |
| `comment` | character varying(400) | oui |  |

## `rm_ledger_hourly`

Modèle de lecture (CQRS) : événements de facturation par statut, par heure et service ; alimenté par les projections.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `hour_ts` | timestamp with time zone | non |  |
| `service_id` | bigint | non |  |
| `status` | character varying(20) | non |  |
| `events` | bigint | non |  |
| `gross` | numeric | non |  |
| `partner_share` | numeric | non |  |
| `provider_share` | numeric | non |  |

## `rm_traffic_hourly`

Modèle de lecture (CQRS) : MO par issue et MT par statut courant, par heure de création, opérateur et service ; alimenté par les projections.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `hour_ts` | timestamp with time zone | non |  |
| `operator_id` | bigint | non |  |
| `service_id` | bigint | non |  |
| `kind` | character varying(2) | non |  |
| `state` | character varying(20) | non |  |
| `n` | bigint | non |  |

## `service_reply`

Réponses MT personnalisées par service, langue (fr/ar/en) et type.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `lang` | character varying(2) | non |  |
| `kind` | character varying(20) | non |  |
| `text` | character varying(500) | non |  |

## `short_code`

Numéro court rattaché à un opérateur, avec période de validité et statut (suspension immédiate).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `number` | character varying(20) | non |  |
| `operator_id` | bigint | non | → `operator` |
| `valid_from` | timestamp with time zone | oui |  |
| `valid_to` | timestamp with time zone | oui |  |
| `status` | character varying(20) | non | 'ACTIVE'::character varying |

## `subscription`

Abonnement d'un MSISDN à un service (statut, prochain renouvellement).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `msisdn` | character varying(100) | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `status` | character varying(20) | non |  |
| `activated_at` | timestamp with time zone | oui |  |
| `next_renewal_at` | timestamp with time zone | oui |  |
| `stopped_at` | timestamp with time zone | oui |  |
| `renewal_failures` | integer | non | 0 |

## `tariff`

Tarif versionné par service et type d'événement ; actif uniquement après approbation (4 yeux).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `event_type` | character varying(20) | non |  |
| `gross_amount` | numeric | non |  |
| `operator_percent` | numeric | non |  |
| `tax_percent` | numeric | non | 0 |
| `effective_from` | timestamp with time zone | non |  |
| `approved` | boolean | non | false |
| `created_by` | character varying(80) | non |  |

## `vas_service`

Service VAS (vote, quiz, contenu premium, abonnement, alerte) : statut, fenêtres, consentement, réponses par défaut.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `name` | character varying(120) | non |  |
| `type` | character varying(20) | non |  |
| `partner_id` | bigint | oui | → `partner` |
| `short_code_id` | bigint | non | → `short_code` |
| `status` | character varying(20) | non | 'DRAFT'::character varying |
| `regulated` | boolean | non | false |
| `regulatory_approved` | boolean | non | false |
| `consent_mode` | character varying(20) | non | 'SIMPLE_OPT_IN'::character varying |
| `opens_at` | timestamp with time zone | oui |  |
| `closes_at` | timestamp with time zone | oui |  |
| `max_actions_per_msisdn` | integer | non | 0 |
| `reply_ok` | character varying(500) | oui |  |
| `reply_stop` | character varying(500) | oui |  |
| `reply_help` | character varying(500) | oui |  |
| `reply_limit` | character varying(500) | oui |  |
| `reply_closed` | character varying(500) | oui |  |
| `default_lang` | character varying(2) | non | 'fr'::character varying |
| `closed_at` | timestamp with time zone | oui |  |
| `max_tps` | integer | non | 0 |

## `vote_ballot`

Bulletins de vote : un par numéro et par service (unicité), option choisie et horodatage.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `msisdn` | character varying(100) | non |  |
| `option_code` | character varying(40) | non |  |
| `created_at` | timestamp with time zone | non |  |
| `mo_id` | bigint | oui |  |

## `vote_option`

Options de vote déclarées d'un service (code court + libellé) ; sans option, tout texte est accepté.

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `service_id` | bigint | non | → `vas_service` |
| `code` | character varying(40) | non |  |
| `label` | character varying(120) | non |  |

## `webhook_outbox`

Outbox des callbacks partenaires (retry exponentiel, statut DEAD = DLQ).

| Colonne | Type | Null | Défaut / référence |
|---|---|---|---|
| `id` | bigint | non |  |
| `event_id` | character varying(100) | non |  |
| `url` | character varying(400) | non |  |
| `secret` | character varying(120) | oui |  |
| `payload` | character varying(4000) | non |  |
| `status` | character varying(10) | non |  |
| `attempts` | integer | non | 0 |
| `next_attempt_at` | timestamp with time zone | non |  |
| `last_error` | character varying(400) | oui |  |
