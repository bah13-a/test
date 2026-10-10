# Moteurs de service, facturation, API et comptes (points 6 à 15)

Ce document décrit ce qui a été ajouté après le socle MO/MT/DLR. Chaque règle est couverte par un test (`FlowTests`, `LifecycleTests`, `ui.test.jsx`, `tests/integration/full-stack.mjs`).

## 1. Moteurs de service
| Type | Fonctionnement | Administration |
|---|---|---|
| **VOTE** | Sans option déclarée, tout texte est compté (compatibilité). Avec options, seul un code déclaré est accepté ; un code inconnu reçoit la réponse `INVALID_CHOICE` (non facturée). Un bulletin par numéro et par service. | Fiche service → *Options de vote* |
| **QUIZ** | Questions posées dans l'ordre ; réponse comparée sans casse ni accents aux réponses acceptées (séparées par `|`) ; points cumulés ; une seule partie par numéro ; le MT de fin donne le score. | Fiche service → *Questions du quiz* |
| **PREMIUM_CONTENT** | Un mot-clé renvoie un **lien à usage limité** (`/c/<jeton>`) : nombre d'usages et durée configurables ; jeton aléatoire de 144 bits (SecureRandom), usage et expiration contrôlés côté serveur. | Fiche service → *Contenus premium* |
| **Campagne** | Statistiques (participants, MO/MT par statut, taux de livraison), trafic MO horaire, résultats officiels, exports PDF/CSV/XLSX, **clôture irréversible** (le service passe `CLOSED`, plus aucun vote/réponse). | Menu *Campagnes* |

Les réponses utilisent le catalogue `Messages` (FR/AR/EN) ; la langue suit celle du message reçu (arabe détecté) puis la langue du service.

## 2. Abonnements : renouvellement et échecs (point 10)
- Le MT de renouvellement est lié à l'abonnement (`mt_message.subscription_id`) ; l'abonnement reçoit un **délai de garde** (`vas.subscription.guard-days`, 3 j) pour ne pas être renouvelé deux fois pendant que le résultat est inconnu.
- DLR livré → `renewal_failures = 0`, prochain renouvellement dans `renewal-days` (30 j).
- DLR non livré / expiré / échec d'envoi → nouvel essai dans `retry-days` (1 j) ; après `max-failures` (3) échecs consécutifs : abonnement **SUSPENDED**, trace de consentement `SUSPENDED_BILLING`, plus de renouvellement.
- Paramètres : `vas.subscription.{renewal-days,retry-days,max-failures,guard-days}`.

## 3. Débits (point 11)
`RateGate` espace les envois (un toutes les 1/TPS s) sur **trois clés** acquises avant chaque `submit_sm` : partenaire (`partner.max_tps`), service (`vas_service.max_tps`) et opérateur (`operator.max_tps`). 0 = illimité. Le facteur de sécurité `vas.rate-safety-factor` (0,9) vise 90 % du débit contractuel, car les SMSC comptent par fenêtre fixe. Réglable en direct : fiche service, fiche partenaire, écran opérateurs.

## 4. Envoi programmé (point 12)
`POST /api/v1/messages` accepte `scheduleAt` (ISO-8601, jusqu'à 30 jours). Le MT reste `PENDING` sans être publié ; `MtSweeper` le publie **une seule fois** à l'échéance. Une date trop lointaine donne 422.

## 5. Facturation (point 13)
Seuls les événements **CHARGED** comptent dans un relevé ; les montants `PENDING/ACCEPTED` sont présentés à part comme « estimés ».

| Opération | Règle |
|---|---|
| **Remboursement** (`POST /admin/billing/adjustments`) | Motif obligatoire. Période **ouverte** : l'événement passe `REVERSED`. Période **clôturée** : l'original reste intact, un événement `ADJUSTMENT` négatif (`ADJ-<eventId>`, statut CHARGED) est créé dans la période courante ; une seule fois (idempotent). |
| **Clôture de période** (`POST /admin/billing/periods`) | Période terminée, sans chevauchement, **aucun** événement PENDING/ACCEPTED/DISPUTED ; totaux figés. Ensuite `LedgerService.transition` refuse toute modification des événements de cette période (journalisé). |
| **Relevé partenaire** (`GET /admin/billing/statements`, `GET /portal/statements`) | Par service et total ; JSON, CSV, XLSX, PDF. Le portail ne montre que le partenaire du compte. |
| **Reversement** (`/admin/billing/payouts`) | Création : pas de chevauchement, aucun événement non rapproché, montant > 0 → `PENDING`. Paiement : **un autre utilisateur** que le créateur (4 yeux), référence obligatoire → `PAID`. Annulation possible tant que `PENDING`. |

Rôles : écriture `SUPER_ADMIN`/`FINANCE` ; lecture + `AUDITOR`. Tout est audité (`PERIOD_CLOSE`, `LEDGER_ADJUST`, `LEDGER_REVERSE`, `PAYOUT_*`).

## 6. API : OAuth2 et webhooks (point 14)
- **OAuth2 `client_credentials`** : `POST /oauth/token` (`application/x-www-form-urlencoded` : `grant_type`, `client_id=client-<id>`, `client_secret=<clé API>`) → jeton Bearer d'1 h, mêmes scopes et quotas que la clé. Le secret doit appartenir au `client_id` indiqué ; 30 essais/min ; refus audités (`OAUTH_DENIED`). `X-API-Key` reste accepté. Un jeton de session administrateur n'ouvre pas l'API (signature à contexte distinct).
- **Anti-SSRF des webhooks** (`UrlGuard`) : https obligatoire, pas d'identifiants dans l'URL, refus des adresses privées, loopback, link-local (dont 169.254.169.254), CGNAT, multicast, ULA IPv6 ; liste blanche optionnelle `WEBHOOK_ALLOWED_HOSTS`. Contrôle à l'enregistrement (422) **et** à chaque envoi (DNS résolu à ce moment) ; les POST ne suivent pas les redirections. La résolution DNS contrôlée est celle qui ouvre la connexion (voir `18-points-techniques-traites.md`) ; l'isolation réseau sortante de l'hôte reste une défense en profondeur recommandée. Le profil `dev` autorise http/privé (`vas.webhook.allow-http`, `allow-private`).

## 7. Comptes, sessions et données (point 15)
- **Changement de mot de passe** : `POST /admin/me/password` (ancien mot de passe vérifié, 12 car. mini, lettres + chiffres, différent de l'ancien).
- **Mot de passe imposé** : un compte créé ou réinitialisé par un administrateur a `must_change_password` ; tout accès hors `/admin/me/**` répond 403 `password_change_required` (Bearer **et** Basic) ; l'interface n'affiche que l'écran de changement.
- **Révocation des sessions** : les jetons embarquent `token_version` ; elle est incrémentée au changement de mot de passe, à `POST /admin/me/logout-all`, et quand un administrateur change les rôles, désactive le compte, réinitialise le MFA ou le mot de passe.
- **Chiffrement des numéros au repos** : voir `07-rapport-securite.md`. Clé `DATA_KEY` (≥ 32 car., exigée par `ProductionGuard`). **Sauvegarder la clé hors serveur** : sa perte rend les numéros irrécupérables ; la changer exige un rechiffrement (lire avec l'ancienne clé, écrire avec la nouvelle) avant mise en service de la nouvelle. Altération détectée (IV recalculé).
- **Journal d'audit immuable** (PostgreSQL, `V10__audit_immutable.sql`) : `UPDATE` et `TRUNCATE` interdits ; `DELETE` uniquement si `vas.audit_purge='on'` dans la transaction, ce que seul `RetentionJob` positionne.

## 8. Correctifs de sécurité trouvés en route
- `/admin/messages` (et tout chemin commençant par `/admin/me`) échappait à l'obligation d'enrôlement MFA : exemption restreinte à `/admin/me` et `/admin/me/**`.
- `PATCH /admin/users/{id}` plantait (paramètre `PasswordEncoder` lu comme attribut de modèle) : injecté par le constructeur.

## 9. Multi-liens SMSC (SMPP-008)
`provision.sh` crée, pour chaque opérateur, une liaison par hôte `<OP>_SMSC_HOST`, `_2`, `_3`, `_4` (même compte SMPP) et une route MT : `FailoverMTRoute` par défaut (bascule sur la liaison suivante si la première est indisponible) ou `RandomRoundrobinMTRoute` avec `<OP>_LINK_MODE=roundrobin`. Les MO/DLR reçus sur `smppc_<op>_2` sont rattachés au même opérateur.
