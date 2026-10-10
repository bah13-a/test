#!/bin/sh
# Exécuté au démarrage du conteneur nginx : génère /etc/nginx/admin-allow.conf depuis ADMIN_ALLOWED_CIDRS (liste séparée par des virgules).
# Vide : aucune restriction. Renseigné : seules ces adresses/réseaux atteignent /admin/, toutes les autres reçoivent 403.
set -e
out=/etc/nginx/admin-allow.conf
: > "$out"
if [ -n "${ADMIN_ALLOWED_CIDRS:-}" ]; then
  for c in $(echo "$ADMIN_ALLOWED_CIDRS" | tr ',' ' '); do echo "allow $c;" >> "$out"; done
  echo "deny all;" >> "$out"
fi
