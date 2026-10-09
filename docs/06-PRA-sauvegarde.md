# Plan de sauvegarde, restauration et reprise (PRA)

## Objectifs (CDC §12.1)
RPO ≤ 15 min (sauvegarde + archivage WAL ; ≈ 0 avec réplication synchrone Patroni) - RTO ≤ 2 h.

## Sauvegardes
| Quoi | Comment | Fréquence | Rétention |
|---|---|---|---|
| Base PostgreSQL | `infra/backup/backup.sh` : `pg_dump` format custom, chiffré GPG vers la clé publique du client, SHA-256 | quotidienne (+ WAL 15 min en production) | 30 jours + copie hors site (`OFFSITE_TARGET`) |
| Configuration | dépôt Git (infra, scripts, migrations) ; `.env` dans le coffre de secrets du client | à chaque changement | historique Git |
| Jasmin | `jcli` → `persist` ; volume `jasminconf` ; script `provision.sh` rejouable | à chaque changement | 30 jours |
| RabbitMQ / Redis | non critiques : la base est la source de vérité (les MT `PENDING` sont republiés) | - | - |

La clé **privée** GPG reste au client (hors serveur).

## Restauration
```bash
infra/backup/restore.sh /backups/vas-AAAAMMJJThhmmssZ.dump.gpg vas_nouvelle   # refuse la base de production sans --force-prod
```
Procédure complète (sinistre) : 1) provisionner l'infra (compose/HA) ; 2) restaurer la dernière sauvegarde ; 3) démarrer l'application (Flyway valide le schéma) ; 4) re-provisionner Jasmin (`provision.sh`) ; 5) rejouer le rapprochement de la période de perte éventuelle ; 6) contrôle : MO/MT de test sur chaque opérateur.

## Test de restauration (preuve)
`infra/backup/verify-restore.sh` restaure la dernière sauvegarde dans une base temporaire, contrôle les tables clés et les migrations, mesure la durée puis nettoie. À planifier hebdomadairement ; l'exécution du jour de la recette sert de procès-verbal (résultat dans `12-rapport-tests.md`).

## Bascule HA
`infra/ha/docker-compose.ha.yml` : Patroni (bascule automatique PostgreSQL, HAProxy suit `/primary`), RabbitMQ en cluster quorum, 2 instances applicatives. Test de bascule à exécuter en préproduction : arrêt du primaire (`docker stop pg1`) → promotion du réplica en < 30 s → l'application se reconnecte (pool JDBC) sans perte de MO (Jasmin rejoue les 500).
