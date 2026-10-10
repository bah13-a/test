#!/usr/bin/env bash
# Charge le fichier .env UNIQUE du projet (ENV_FILE, par défaut ./.env à la racine du dépôt) et en déduit les variables PostgreSQL
# standard (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD) à partir de DB_URL / DB_USER / DB_PASSWORD si elles ne sont pas déjà définies.
# Usage : source "$(dirname "$0")/../lib/env.sh"
_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
_envfile="${ENV_FILE:-$_root/.env}"
if [[ -f "$_envfile" ]]; then
  while IFS= read -r _line || [[ -n "$_line" ]]; do
    [[ "$_line" =~ ^[[:space:]]*# || -z "${_line//[[:space:]]/}" || "$_line" != *=* ]] && continue
    _k="${_line%%=*}"; _v="${_line#*=}"
    # valeur entre apostrophes = littérale (hash bcrypt contenant des $) ; sinon : commentaire de fin de ligne et espaces retirés
    if [[ "$_v" == \'* ]]; then _v="${_v#\'}"; _v="${_v%%\'*}"                      # entre apostrophes : littéral jusqu'à l'apostrophe fermante
    else _v="${_v%%[[:space:]]#*}"; _v="${_v%"${_v##*[![:space:]]}"}"; _v="${_v#\"}"; _v="${_v%\"}"; fi
    [[ "$_k" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    [[ -z "${!_k+x}" ]] && export "$_k=$_v"   # une variable déjà exportée dans l'environnement l'emporte sur le fichier
  done < "$_envfile"
fi
if [[ -n "${DB_URL:-}" ]]; then
  _hp="${DB_URL#jdbc:postgresql://}"; _hp="${_hp%%/*}"
  export PGHOST="${PGHOST:-${_hp%%:*}}"
  [[ "$_hp" == *:* ]] && export PGPORT="${PGPORT:-${_hp##*:}}"
  _db="${DB_URL#jdbc:postgresql://*/}"; export PGDATABASE="${PGDATABASE:-${_db%%\?*}}"
fi
[[ -n "${DB_USER:-}" ]] && export PGUSER="${PGUSER:-$DB_USER}"
[[ -n "${DB_PASSWORD:-}" ]] && export PGPASSWORD="${PGPASSWORD:-$DB_PASSWORD}"
[[ -n "${BACKUP_GPG_RECIPIENT:-}" ]] && export BACKUP_GPG_RECIPIENT
true
