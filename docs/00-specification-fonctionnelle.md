# Spécification fonctionnelle — Plateforme VAS / SMS Premium (SMPP v3.4, Tunisie)

Version décrivant l'application telle que réalisée (lots A à H du cahier des charges, points 1 à 19). Les détails techniques sont dans `01-architecture-HLD.md` et `02-conception-LLD.md` ; la description détaillée des moteurs, de la facturation et des comptes est dans `16-moteurs-facturation-comptes.md`.

## 1. Objet et périmètre

La plateforme permet à un fournisseur de services à valeur ajoutée (VAS) de **recevoir et d'envoyer des SMS** sur les réseaux de Tunisie Telecom, Orange Tunisie et Ooredoo Tunisie, d'exploiter des **services par short code** (vote, quiz, contenu premium, abonnement, alerte), de **facturer** ces services selon les règles de chaque opérateur et de **rendre compte** aux partenaires de contenu.

Hors périmètre : la connexion contractuelle aux opérateurs (VPN, comptes SMPP), les relevés dans le format propre à chaque opérateur (paramétrables par mapping de colonnes), la comptabilité générale, le paiement bancaire effectif des reversements (la plateforme les prépare et les trace).

### Acteurs
| Acteur | Rôle |
|---|---|
| **Abonné / participant** | Envoie un SMS à un short code, reçoit les réponses. |
| **Opérateur** (TT, Orange, Ooredoo) | Achemine les SMS via SMPP, rend les accusés (DLR), fournit les relevés de facturation. |
| **Partenaire** | Fournisseur de contenu ou organisateur de campagne : consulte ses résultats et ses revenus, envoie des SMS par API, reçoit des webhooks. |
| **Équipes internes** | Sept rôles du back-office (section 3). |
| **Système Jasmin** | Passerelle SMPP ; ne porte aucune logique métier. |

## 2. Vocabulaire
**MO** : SMS reçu d'un abonné. **MT** : SMS envoyé à un abonné. **DLR** : accusé de livraison. **Short code** : numéro court attribué par un opérateur. **MSISDN** : numéro de téléphone (stocké chiffré). **Ledger** : journal des événements facturables. **Période** : intervalle de facturation clôturé.

## 3. Rôles et droits (back-office)

| Rôle | Peut |
|---|---|
| **SUPER_ADMIN** | Tout, dont utilisateurs, clients API, approbation réglementaire, journal d'audit. |
| **NOC** | Superviser, suspendre/réactiver opérateurs et short codes, régler les débits opérateur. |
| **VAS_MANAGER** | Gérer short codes, partenaires, services, mots-clés, réponses, listes noire/blanche, options de vote, quiz, contenus ; clôturer une campagne. |
| **FINANCE** | Tarifs, ledger, rapprochement, ajustements, clôture de période, relevés, reversements. |
| **SUPPORT** | Messages, consentements, désabonnements, listes noire/blanche. |
| **AUDITOR** | Lecture seule (services, tarifs, ledger, facturation, consentements, audit). |
| **PARTNER** | Portail limité à **ses** services et à **ses** montants. |

Règles transverses : toute modification est **auditée** ; les accès refusés sont tracés ; les numéros sont masqués hors rôles autorisés ; les actions sensibles exigent **deux personnes** (approbation de tarif, paiement d'un reversement).

## 4. Fonctions

### 4.1 Réception et routage des SMS (MO)
1. Le SMS arrive de l'opérateur via Jasmin ; il est identifié par opérateur (connecteur) et short code de destination. Les liaisons de secours d'un même opérateur sont reconnues.
2. **Déduplication** : un MO rejoué (même identifiant, ou même contenu du même numéro dans une courte fenêtre configurable) n'a aucun nouvel effet.
3. **Contrôles** dans l'ordre : numéro en liste noire ou hors liste blanche (si elle existe) → bloqué ; service clôturé ou hors fenêtre de campagne → réponse « fermé » ; plafond d'actions par numéro atteint → réponse « limite » ; service réglementé non approuvé → bloqué.
4. **Commandes universelles** : `STOP` / `DESABO` (et équivalents arabes) désabonne ; `AIDE` / `HELP` renvoie l'aide.
5. **Routage par mot-clé** vers le service ; mot-clé inconnu : rejet silencieux (aucune réponse, aucune facturation).
6. **Langue** : arabe détecté dans le message sinon langue du service ; réponses FR / AR / EN, surchargeables par service et par type de réponse.
7. Le MO est conservé avec son issue (routé, doublon, inconnu, bloqué, limite, choix invalide…) et notifié au webhook du partenaire.

### 4.2 Types de services
| Type | Comportement |
|---|---|
| **Vote** | Un vote par numéro et par service. Sans option déclarée, tout texte est compté ; avec options, seul un code déclaré est accepté (sinon réponse « choix invalide », non facturée). Résultats officiels par option. |
| **Quiz** | Questions posées dans l'ordre ; réponse comparée sans casse ni accents (variantes arabes normalisées) à une liste de réponses acceptées ; points cumulés ; une seule partie par numéro ; score final envoyé. |
| **Contenu premium** | Un mot-clé renvoie un **lien à usage et durée limités** (`/c/<jeton>`) ; au-delà, le lien est refusé. |
| **Abonnement** | Activation par mot-clé (opt-in simple, double avec confirmation, ou par API avec preuve), renouvellement automatique, désabonnement par STOP. |
| **Alerte** | Envois initiés par le partenaire ou le back-office vers des abonnés consentants. |

### 4.3 Consentement et conformité
Chaque activation, confirmation, désabonnement ou suspension crée une **preuve de consentement** (canal, texte, version des conditions, horodatage), consultable et exportable. Les services **réglementés** restent bloqués tant que l'**approbation réglementaire** n'est pas enregistrée par un SUPER_ADMIN.

### 4.4 Abonnements et renouvellements
- Renouvellement par défaut tous les 30 jours ; le MT de renouvellement est lié à l'abonnement.
- Échec (non livré, expiré, erreur d'envoi) : nouvel essai le lendemain ; après **3 échecs consécutifs** l'abonnement passe en **SUSPENDED** (trace de consentement), sans nouveau renouvellement.
- Succès : compteur d'échecs remis à zéro, prochain renouvellement dans 30 jours.
- Un désabonnement supprime toute échéance.

### 4.5 Envoi des SMS (MT)
- Sources : réponses du moteur, renouvellements, campagnes, **API partenaire**, back-office.
- **Priorités** : transactionnel, confirmation, en masse — files séparées.
- **Encodage** et **segmentation** automatiques (GSM 03.38 ou UCS-2 pour l'arabe/accents ; messages longs découpés avec concaténation).
- **Envoi programmé** : date d'envoi jusqu'à 30 jours ; le message attend l'échéance.
- **Débit** : limité par opérateur, par service et par partenaire (SMS/s ; 0 = illimité), lissé avec une marge sous le débit contractuel.
- **Fiabilité** : aucun message perdu (file durable, reprises automatiques, balayeur) ; liaison SMPP coupée → les messages attendent la reconnexion ; basculement automatique entre liaisons de secours d'un opérateur.
- **Cycle de vie d'un MT** : `PENDING → SUBMITTED → DELIVERED | EXPIRED | UNDELIVERABLE | REJECTED`, ou `UNKNOWN` (aucun accusé après le délai configuré) / `FAILED` (échec définitif après reprises). Un accusé tardif corrige un statut `UNKNOWN`.
- Un message expiré (validité 24 h par défaut) n'est pas envoyé.

### 4.6 Tarification et facturation
**Tarifs** : par service et type d'événement (MO, MT, abonnement, renouvellement) ; prix TTC, taxes, part opérateur ; versionnés, **non rétroactifs**, valables à partir d'une date, **approuvés par une seconde personne** avant application ; simulateur de répartition.

**Ledger** : un événement par fait générateur, **idempotent** (jamais deux facturations pour un même fait). Répartition : taxes, part opérateur, part partenaire, part fournisseur ; montants au millime de dinar.
Statuts : `PENDING → ACCEPTED → CHARGED`, ou `REJECTED`, `DISPUTED`, `REVERSED`. La **règle de facturation par opérateur** décide si l'événement est confirmé à la soumission ou à la livraison ; un MT non livré n'est pas facturé ; un MT sans accusé devient `DISPUTED`.

**Rapprochement** : import du relevé opérateur (CSV ou XLSX, colonnes à mapper) ; comparaison avec le ledger : conforme, écart de montant, écart de statut, manquant côté plateforme ou côté opérateur ; correction manuelle journalisée ; export CSV / XLSX / PDF.

**Ajustements et remboursements** (motif obligatoire) : période ouverte → l'événement passe en `REVERSED` ; période clôturée → un événement d'ajustement **négatif** est créé dans la période courante, l'original restant intact.

**Clôture de période** : possible si la période est terminée, sans chevauchement et sans événement en attente ou contesté ; les totaux sont figés et les événements de la période ne peuvent plus changer.

**Relevés partenaires** : par service et au total, montants *reconnus* (CHARGED) et *estimés* (en attente), exports JSON / CSV / XLSX / PDF.

**Reversements** : création (aucun chevauchement, aucun événement non rapproché, montant positif) → paiement par **une autre personne** avec référence → `PAID` ; annulation possible tant qu'en attente.

### 4.7 Campagnes
Tableau de bord par service : participants, MO par issue, MT par statut, taux de livraison, trafic MO horaire (graphique doublé d'un tableau), résultats lisibles (votes par option, classement du quiz, retraits de contenu), montants pour les rôles financiers. Exports PDF / CSV / XLSX. **Clôture irréversible** : le service passe en `CLOSED`, plus aucune participation n'est acceptée.

### 4.8 API partenaires (`/api/v1`)
- Authentification par **clé API** (stockée hachée) ou **OAuth2 client_credentials** (jeton Bearer d'1 h).
- **Scopes** : envoi de messages, lecture de messages, lecture des services et rapports, écriture d'abonnements ; quota par minute et par client ; un partenaire ne voit que ses services.
- Fonctions : envoyer un SMS (idempotent via `clientRef`, programmable), consulter le statut et l'historique d'un message, lister ses services, obtenir des rapports, créer/arrêter des abonnements avec preuve de consentement, obtenir un lien de contenu.
- **Webhooks** signés (HMAC, horodatage anti-rejeu) pour les **MO** et les **DLR**, avec reprises exponentielles puis file morte ; l'URL doit être en https vers une adresse publique.
- Documentation : OpenAPI (`docs/openapi.json`, Swagger UI).

### 4.9 Portail partenaire
Services du partenaire, volumes MO/MT, taux de livraison, résultats par contenu, montants estimés et rapprochés, relevés par période, historique des reversements, exports. Aucun accès aux données d'un autre partenaire (tentative tracée).

### 4.10 Back-office (React, FR / AR avec RTL / EN)
Écrans : tableau de bord, opérateurs, short codes, services (avec mots-clés, réponses, options de vote, questions de quiz, contenus), partenaires, tarifs, campagnes, messages (recherche multicritère), ledger, facturation, rapprochement, listes noire/blanche, support (consentements, désabonnement), utilisateurs, clients API, audit, sécurité. Listes paginées côté serveur, validations accessibles, confirmations pour les actions destructrices, conforme WCAG 2.1 AA.

### 4.11 Comptes et sécurité fonctionnelle
- Mot de passe : 12 caractères minimum, lettres et chiffres ; verrouillage 15 min après 5 échecs ; **changement obligatoire** à la première connexion et après réinitialisation.
- **MFA TOTP** obligatoire pour SUPER_ADMIN et FINANCE en production.
- Session de 30 min ; **révocation de toutes les sessions** (volontaire, changement de mot de passe, rôles, désactivation, reset MFA).
- Numéros de téléphone **chiffrés en base** ; journal d'audit **non modifiable** ; purge de conservation paramétrable (messages, consentements, audit, webhooks) ; le ledger n'est jamais purgé.

### 4.12 Supervision et exploitation
Métriques Prometheus, tableaux Grafana, alertes (liaison coupée, file saturée, taux d'échec), journaux JSON agrégés (Loki), santé applicative, sauvegarde chiffrée et test de restauration, topologie haute disponibilité de référence.

## 5. Profils d'exécution
| | **dev** | **pro** |
|---|---|---|
| Passerelle | simulateur avec accusés automatiques et pannes simulables | Jasmin réel |
| Données | démonstration (opérateurs, services, comptes par rôle) | aucune donnée fictive |
| Contrôles | bandeau « démonstration » | validation stricte au démarrage (secrets, MFA, base PostgreSQL, absence de mocks) |

## 6. Exigences non fonctionnelles
| Domaine | Exigence | Mesure constatée |
|---|---|---|
| Performance MO | P95 < 2 s | 152 req/s, P95 736 ms |
| Performance API | P95 < 500 ms | 186 req/s, P95 467 ms |
| Disponibilité | aucune perte de message sur coupure de lien ou de composant | validé sur pile réelle |
| Idempotence | rejeu MO, DLR, facturation sans double effet | validé |
| Sécurité | OWASP Top 10, isolation des partenaires, secrets hors dépôt | rapport `07-rapport-securite.md` |
| Accessibilité | WCAG 2.1 AA | 0 violation (axe-core) |
| Reprise | RTO 2 h | restauration vérifiée en 1 s sur le volume de test |

## 7. Règles de gestion clés (récapitulatif)
1. Un fait générateur = au plus un événement de ledger.
2. Un MT non livré n'est jamais définitivement facturé.
3. Un tarif approuvé est immuable ; un changement crée une version à date d'effet.
4. Une période clôturée ne change plus ; on corrige par ajustement négatif.
5. Création et validation d'un paiement (ou d'un tarif) sont faites par deux personnes différentes.
6. Un service réglementé n'émet rien tant que l'approbation n'est pas enregistrée.
7. Un numéro en liste noire ne reçoit et ne déclenche rien.
8. Un partenaire ne voit jamais les données d'un autre.
9. Trois échecs de renouvellement suspendent l'abonnement.
10. Un MT n'est jamais perdu : il est réessayé, expiré, ou marqué en échec visible.

## 8. Limites connues
Jamais testé contre un vrai SMSC opérateur (formats d'accusé et relevés propres à chaque opérateur à valider en recette) ; pas de test d'intrusion ni de test de charge à 200 SMS/s sur l'infrastructure cible ; la perte de la clé de chiffrement des numéros les rend irrécupérables.
