# Performance de la base de données

Mesures réelles sur **PostgreSQL 16** (`tests/perf/db-bench.sh`, reproductible) avec 2 000 000 de MT, 2 000 000 de MO, 1 000 000 d'événements de ledger et 200 000 lignes d'audit (base de 1,7 Go, dont 1,0 Go d'index). Temps d'exécution `EXPLAIN ANALYZE`, avant et après la migration `V7__indexes.sql`.

| Requête (chemin chaud) | Avant | Après |
|---|---|---|
| Q1 Recherche de messages d'un numéro (50 derniers) | 87 ms | **0,06 ms** |
| Q2 Tableau de bord : MT par statut sur 24 h | 91 ms | 27 ms |
| Q3 Balayeur : MT `PENDING` anciens | 85 ms | 9 ms |
| Q4 Expiration DLR : MT `SUBMITTED` non mis à jour | 38 ms | 2 ms |
| Q5 Grand livre : page `CHARGED` sur 7 jours | 89 ms | **0,05 ms** |
| Q6 Dédoublonnage MO par contenu (30 s) | 66 ms | **0,02 ms** |
| Q7 Comptage des MO par issue sur 24 h | 72 ms | 22 ms |
| Q8 Journal d'audit : page la plus récente | 0,02 ms | 0,02 ms |
| Q9 Idempotence API (`clientRef`) | 86 ms | **0,03 ms** |

Les requêtes de comptage sur une fenêtre (Q2, Q7) restent proportionnelles au nombre de lignes de la fenêtre (≈ 66 000 ici) : acceptables pour un tableau de bord rafraîchi à la demande ; en cas de volumes très supérieurs, les agréger dans une table de synthèse horaire.

## Compromis
Les 21 index coûtent de l'espace (1,0 Go pour 5 M de lignes) et ralentissent un peu les insertions : à 50 SMS/s par opérateur l'écriture reste très en deçà de la capacité de PostgreSQL ; ne pas ajouter d'index sans mesurer.

## Pagination
Toutes les listes d'administration sont paginées (`page`, `size` ≤ 500 ; total dans l'en-tête `X-Total-Count`). Les exports sont lus par pages de 2 000 lignes et **plafonnés à 50 000 lignes** : au-delà, l'API répond 413 et demande de restreindre la période (`from`, `to`).

## Partitionnement : volontairement non réalisé
Les chiffres ci-dessus montrent qu'à 2 M de lignes, tous les chemins chauds répondent en moins de 30 ms avec de simples index. Le partitionnement mensuel de `mt_message` n'apporte un bénéfice qu'au-delà de ~100 M de lignes (maintenance, purge par suppression de partition) et a un coût : clé primaire et unicité de `correlation_id` à recomposer avec la clé de partition, perte de la clé étrangère `mt_status_history → mt_message`, recherche par `correlation_id` sur toutes les partitions. Tant que la purge de conservation (`RetentionJob`) suffit, il n'est pas justifié.

Seuils pour le déclencher : `mt_message` > 100 M de lignes **ou** purge nocturne > 30 min **ou** `VACUUM` ne suivant plus. Plan : migration PostgreSQL dédiée (`db/migration/postgresql/`), partitions mensuelles par `created_at` avec `pg_partman`, clé primaire `(id, created_at)`, purge par `DROP PARTITION`, suppression de la clé étrangère de l'historique.
