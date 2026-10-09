# Guide administrateur

## 1. Premier démarrage

1. `cp .env.example .env` et renseigner **tous** les secrets (jamais commités).
2. Générer le hash du mot de passe administrateur : `htpasswd -bnBC 10 "" 'MotDePasse-Fort-123' | tr -d ':\n'` puis préfixer par `{bcrypt}` dans `ADMIN_PASSWORD_HASH`. Idem pour `PROMETHEUS_PASSWORD_HASH` (compte de scraping, rôle NOC).
3. `docker compose up -d --build`. Au premier démarrage (table `app_user` vide), les comptes `ADMIN_USER` et `prometheus` sont créés.
4. Se connecter à `http(s)://<hôte>/`, **activer le MFA** (menu Sécurité) : obligatoire pour `SUPER_ADMIN` et `FINANCE` (sinon seules les pages d'enrôlement sont accessibles).
5. Provisionner Jasmin pour chaque opérateur avec `infra/jasmin/provision.sh` (paramètres de l'Annexe B, voir `operateurs/fiche-parametrage-operateur.md`).

## 2. Rôles

| Rôle | Droits |
|---|---|
| SUPER_ADMIN | Tout, dont utilisateurs, clients API, approbation réglementaire |
| NOC | Opérateurs (statut, TPS), short codes (suspension), supervision ; aucun accès financier |
| VAS_MANAGER | Services, mots-clés, réponses, partenaires, short codes, listes noires/blanches |
| FINANCE | Tarifs (création + approbation par un autre), ledger, rapprochement, exports |
| SUPPORT | Recherche de messages (MSISDN complet), consentements, désinscription, listes |
| PARTNER | Portail : uniquement les services de son partenaire |
| AUDITOR | Lecture seule (ledger, rapprochement, consentements, audit) |

Mot de passe : 12 caractères minimum, lettres et chiffres. 5 échecs → verrouillage 15 minutes. MFA : application TOTP (Google/Microsoft Authenticator, FreeOTP).

## 3. Exploitation courante

| Action | Où | Effet |
|---|---|---|
| Créer un service | Services | Brouillon ; l'activer explicitement. Effet immédiat, sans redéploiement |
| Service réglementé (jeux, concours...) | case « Réglementé » | **Bloqué** tant que `SUPER_ADMIN` n'a pas donné l'approbation réglementaire |
| Suspendre un service / short code | Services / Short codes | Immédiat |
| Tarif | Tarifs | Création par FINANCE, **approbation par un autre utilisateur**, jamais rétroactif ; simulateur de répartition |
| Réponses multilingues | Services → Mots-clés / Réponses | Types OK, STOP, HELP, LIMIT, CLOSED, CONFIRM, ALREADY, SUB_OK, RENEWAL × fr/ar/en |
| Client API | Clients API | La clé n'est affichée qu'**une fois** ; révocation immédiate |
| Règle de facturation DLR | Opérateurs → DLR | `ON_DELIVERED` (défaut) ou `ON_SUBMITTED` par opérateur |
| Exclure un numéro | Listes noire/blanche | Liste blanche non vide = seuls les numéros listés passent (phase de test) |

## 4. Configuration (variables d'environnement)

| Variable | Rôle |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `RABBIT_HOST/USER/PASSWORD`, `REDIS_HOST` | Broker et cache |
| `VAS_QUEUE` | `rabbit` (production) ou `memory` (dev) |
| `VAS_RATE_LIMIT` | `redis` ou `memory` |
| `JASMIN_URL`, `JASMIN_PASSWORD` | API HTTP Jasmin (utilisateurs `vas_<opérateur>`) |
| `CALLBACK_SECRET` | Secret des callbacks Jasmin (à reporter dans `provision.sh`) |
| `VAS_PUBLIC_BASE_URL` | URL de rappel des DLR vue par Jasmin |
| `VAS_MFA_ENFORCED` | MFA obligatoire pour SUPER_ADMIN/FINANCE (défaut `true`) |
| `SPRING_PROFILES_ACTIVE=json` | Logs JSON pour Loki |

## 5. Rotation de secrets

`CALLBACK_SECRET` / `JASMIN_PASSWORD` : modifier dans `.env` **et** dans Jasmin (`provision.sh`), redémarrer l'application, puis Jasmin. Clés API : créer une nouvelle clé, basculer le partenaire, révoquer l'ancienne. Mots de passe : menu Utilisateurs. Aucune valeur secrète n'est dans le dépôt Git.
