# Architecture CQRS (séparation commandes / requêtes)

## Décision
Les lectures de reporting (tableaux de bord, statistiques de campagne, recherches, ledger, relevés, portail partenaire) sont séparées des écritures. Le côté **commande** garde la logique métier et la cohérence transactionnelle ; le côté **requête** lit des **modèles de lecture** dédiés et, si un réplica est configuré, depuis ce réplica.

Ce n'est **pas** de l'« event sourcing » : la base relationnelle reste la source de vérité ; les événements de domaine ne servent qu'à alimenter les modèles de lecture. Ce choix évite de réécrire l'historique et les migrations, tout en donnant les gains attendus de CQRS (lectures rapides, découplage, mise à l'échelle séparée).

```
 Commandes (HTTP POST/PATCH/DELETE, callbacks Jasmin, files, planificateurs)
        │
        ▼
 tn.vas.service · tn.vas.web (écriture) ──► tables d'écriture (PostgreSQL primaire)
        │  publie des événements après chaque changement
        ▼
 tn.vas.event.DomainEvents : MoRecorded · MtTransitioned · LedgerChanged
        │  (après commit, transaction indépendante : une erreur ne bloque jamais une commande)
        ▼
 tn.vas.projection.Projections ──► modèles de lecture rm_traffic_hourly · rm_ledger_hourly
                                              ▲
 tn.vas.query (GET uniquement, lecture seule) ┘  + tables d'écriture en lecture (recherches paginées)
        │            routées vers le RÉPLICA si READ_DB_URL est renseigné
        ▼
 tableau de bord · campagnes · messages · ledger · rapprochement · relevés · audit · portail partenaire
```

## Ce qui est côté commande / côté requête
| Côté commande (écrit, valide, publie) | Côté requête (lit seulement) |
|---|---|
| `MoService`, `MtService`, `DlrService`, `LedgerService`, `BillingService` (ajustement, clôture, reversement), `CampaignService.close`, `SubscriptionService`, `ReconciliationService.comment`, contrôleurs d'administration en écriture, configuration (opérateurs, services, partenaires, tarifs…) | `ReportingQueries` (tableau de bord, messages, ledger, rapprochement, consentements, audit), `CampaignQueries` (statistiques, résultats), `PortalQueries` (portail partenaire), `BillingQueries` (périodes, relevés, reversements) et leurs contrôleurs `*QueryController` |

Les listes de **configuration** (opérateurs, short codes, partenaires, services, tarifs, mots-clés…) restent côté commande, lues sur la primaire : elles alimentent des formulaires d'édition et doivent refléter immédiatement une modification.

## Modèles de lecture (migration `V12__read_models.sql`)
| Table | Contenu | Clé |
|---|---|---|
| `rm_traffic_hourly` | MO par issue et MT par **statut courant**, par heure de création, opérateur et service (service 0 = aucun) | heure, opérateur, service, type (MO/MT), état |
| `rm_ledger_hourly` | événements de facturation par statut : nombre, brut, part partenaire, part fournisseur | heure, service, statut |

Une transition de MT (`SUBMITTED → DELIVERED`) retire 1 de l'ancien état et ajoute 1 au nouveau dans le même seau horaire (celui de la création du MT) : la lecture reste exclusive entre statuts, comme avant. Les seaux sont **horaires** : « les 24 dernières heures » commence à l'heure pleine précédente (jusqu'à 1 h de plus qu'avant).

Le relevé partenaire et les reversements ne passent **pas** par ces modèles : ils sont calculés sur le ledger, exactement, car ils servent de base à un paiement.

## Cohérence et reprise
- Les projections s'exécutent **après commit** dans leur propre transaction : cohérence **à terme** (quelques millisecondes en pratique), jamais d'impact sur une commande. Chaque échec est compté (`vas.projection.error`) et journalisé.
- **Réconciliation** : `Projections.reconcile()` recalcule les 48 dernières heures depuis les tables sources, chaque nuit (`vas.projection-reconcile-cron`, 03:15 par défaut). Elle rattrape tout événement perdu (arrêt entre commit et projection, indisponibilité de la table). Un événement perdu n'est donc visible dans les chiffres que jusqu'à la prochaine réconciliation.
- **Premier démarrage** : si les modèles sont vides alors que des données existent (montée de version, import), ils sont construits automatiquement.
- Une correction manuelle de rapprochement ou un remboursement publient aussi leurs événements ; une période clôturée refuse désormais la correction manuelle (utiliser un ajustement).

## Séparation physique (réplica de lecture)
`READ_DB_URL` (+ `READ_DB_USER`, `READ_DB_PASSWORD`) : les transactions `readOnly` — tout le côté requête — vont au réplica PostgreSQL ; commandes, projections et migrations vont à la primaire. Sans cette variable, une seule base est utilisée, comportement inchangé. Implémentation : `ReadReplicaConfig` (routage par `isCurrentTransactionReadOnly()` derrière un `LazyConnectionDataSourceProxy`). Compromis : un écran de reporting peut retarder du délai de réplication ; surveiller le retard de réplication et prévoir une alerte (> 5 s).

## Règles vérifiées à chaque build (`ArchitectureTests`, ArchUnit)
1. Le côté requête ne dépend d'aucun service de commande (seuls `AuditService` et l'utilitaire `Text` sont tolérés).
2. Le côté requête n'appelle aucune écriture (dépôts `save`/`delete`, `JdbcTemplate.update`, `EntityManager.persist`…).
3. Les contrôleurs du côté requête n'exposent que des `GET`.
4. Le côté commande ne connaît pas les projections (il publie des événements).
5. Les projections ne dépendent ni des services de commande, ni des contrôleurs, ni des requêtes.
6. Seul le côté commande publie des événements de domaine.

## Vérifications exécutées
- `LifecycleTests` : la projection est identique aux tables d'écriture (MO par issue, MT par statut, facturation) après un flux réel ; suppression des modèles → `reconcile()` les reconstruit ; table de lecture indisponible → la commande aboutit, l'erreur est comptée, puis rattrapée.
- `ReadWriteRoutingTests` : lecture seule → réplica, commande → primaire, hors transaction → primaire.
- Sur PostgreSQL réel (pile d'intégration, routage de lecture actif) : tableau de bord, campagne et messages servis par le côté requête ; `rm_*` égaux aux tables d'écriture (MT, MO, ledger).

## Limites
- Les participants distincts d'une campagne et le trafic horaire par participant sont lus sur `mo_message` (index) : non projetés.
- Le routage vers un réplica n'est exercé qu'en test unitaire et avec la même base en intégration ; un vrai réplica (retard, bascule) est à valider en préproduction.
