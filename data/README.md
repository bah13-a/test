# Données de démarrage

Fichiers lus au démarrage de l'application (montés en lecture seule sur `/data`). Les chemins se déclarent dans le `.env` :

| Variable | Fichier | Modèle |
|---|---|---|
| `ROUTING_RANGES_FILE=/data/ranges.csv` | plages de numéros par opérateur | `ranges.example.csv` |
| `ROUTING_PORTED_FILE=/data/ported.csv` | numéros portés | `ported.example.csv` |
| `CATALOG_FILE=/data/catalog.json` | partenaires, services, mots-clés, tarifs initiaux | `catalog.example.json` |

Les fichiers réels (`ranges.csv`, `ported.csv`, `catalog.json`) ne sont pas versionnés. `python3 tools/check-env.py` les valide avant le démarrage.
