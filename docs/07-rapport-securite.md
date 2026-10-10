# Rapport de sécurité et d'architecture

## 1. Surfaces et contrôles

| Surface | Authentification | Autorisation | Autres contrôles |
|---|---|---|---|
| `/callbacks/**` (Jasmin) | secret partagé (comparaison en temps constant) | rôle interne `GATEWAY` | réseau interne uniquement ; secret hors dépôt |
| `/api/v1/**` | clé API hachée SHA-256 (`X-API-Key`) | scopes `messages:send`, `messages:read`, `services:read`, `reports:read`, `subscriptions:write` | quota/min (Redis), isolation par partenaire (404 + audit), journal d'accès, `clientRef` idempotent |
| `/admin/**`, `/portal/**` | Basic + TOTP (en-tête `X-TOTP`) | 7 rôles RBAC (`@PreAuthorize`) | MFA obligatoire SUPER_ADMIN/FINANCE, verrouillage après 5 échecs, mots de passe 12+ car., hash bcrypt, audit |
| `/actuator/**` | Basic (NOC/SUPER_ADMIN) ; `health` public | - | `prometheus` non public |
| Webhooks sortants | signature HMAC-SHA256 + timestamp + `event_id` | - | retry/DLQ ; le partenaire rejette les rejeux |

## 2. Correspondance OWASP Top 10

| Risque | Mesure |
|---|---|
| A01 Broken access control | RBAC par méthode, isolation partenaire testée (`partnerPortalIsolation`, `apiKeyScopesAndPartnerIsolation`), refus tracés (`ACCESS_DENIED`, `CROSS_ACCOUNT_ACCESS_DENIED`) |
| A02 Cryptographie | TLS terminé par le reverse proxy (HTTPS obligatoire), bcrypt, SHA-256, HMAC-SHA256, sauvegardes GPG ; secrets via environnement |
| A03 Injection | JPA/requêtes paramétrées uniquement ; neutralisation des formules dans les exports CSV/XLSX (`Exports.safe`) ; UI React échappe par défaut |
| A04 Conception | idempotence, 4 yeux sur les tarifs, blocage des services réglementés non approuvés |
| A05 Configuration | secrets hors Git (`.env.example`), CSP `default-src 'self'`, `nosniff`, `no-referrer`, ports d'administration Jasmin non publiés, utilisateur non-root dans l'image |
| A06 Composants | `dependency-check` en CI, licences listées (`09-licences.md`) |
| A07 Authentification | MFA TOTP (RFC 6238 testé sur le vecteur officiel), verrouillage, pas de WWW-Authenticate (pas de popup navigateur) |
| A08 Intégrité | migrations Flyway versionnées, ledger idempotent, signature des webhooks |
| A09 Journalisation | audit applicatif, logs JSON avec `corr`, MSISDN masqués dans l'audit/UI/exports |
| A10 SSRF | URL de webhook définie uniquement par un VAS_MANAGER/SUPER_ADMIN ; à restreindre par allowlist de sortie au niveau pare-feu |

## 3. Données personnelles
MSISDN stocké en clair (nécessaire au service) ; accès contrôlé par rôle, masquage par défaut dans les écrans, logs et exports financiers ; preuves de consentement historisées ; export d'historique par MSISDN. Les durées de conservation et le texte d'information seront fixés avec le conseil juridique du client (CDC §11.3) : la purge est à planifier via une tâche SQL dédiée une fois la durée arrêtée.

## 4. Limites et points à traiter avant ouverture commerciale
- Test d'intrusion externe non réalisé (chiffré au bordereau « Tests de charge & sécurité »).
- Chiffrement au repos : **les numéros de téléphone sont chiffrés par l'application** (AES-256-CTR à IV synthétique HMAC-SHA256, déterministe pour conserver recherches et unicité ; préfixe `enc:v1:` ; altération détectée) avec la clé `DATA_KEY` (≥ 32 caractères, exigée par `ProductionGuard`). Le déterminisme révèle les égalités entre lignes, pas les numéros. **La clé doit être sauvegardée hors serveur** ; sa perte rend les numéros irrécupérables ; une rotation exige un rechiffrement préalable. Le reste de la base relève toujours du chiffrement disque/TDE de l'hébergeur ; les sauvegardes sont chiffrées par l'application.
- Journal d'audit immuable au niveau PostgreSQL (trigger V10 : UPDATE/TRUNCATE interdits, DELETE réservé à la purge de conservation) ; webhooks partenaires protégés contre le SSRF (`UrlGuard`) ; mot de passe initial à changer et révocation des sessions (`16-moteurs-facturation-comptes.md`).
- Allowlist IP/VPN, TLS et WAF : à mettre en place sur l'infrastructure du client.
- Pas de rotation automatique des secrets (procédure manuelle documentée).
