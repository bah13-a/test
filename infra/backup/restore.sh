#!/usr/bin/env bash
# Restauration d'une sauvegarde chiffrée vers une base cible. Usage : restore.sh <fichier.dump.gpg> <base_cible>
# Requiert la clé privée GPG. Ne remplace JAMAIS la base de production sans --force-prod.
set -euo pipefail
file="${1:?fichier}" target="${2:?base cible}"
[[ "$target" == "vas" && "${3:-}" != "--force-prod" ]] && { echo "refus : cible 'vas' (production) sans --force-prod" >&2; exit 2; }
sha256sum -c "$file.sha256"
dropdb --if-exists "$target"
createdb "$target"
gpg --batch --decrypt "$file" | pg_restore --no-owner --dbname="$target" --exit-on-error
echo "restauration OK dans $target"
