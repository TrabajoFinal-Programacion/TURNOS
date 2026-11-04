#!/usr/bin/env sh
# Genera el par de claves RSA con el que turnos firma los JWT de usuario final.
# - jwt-private.pem: solo la usa turnos-service (firma).
# - jwt-public.pem:  la usan turnos-service y catalogo-service (validacion).
# Las claves quedan en ./secrets, que esta en .gitignore.
set -eu
cd "$(dirname "$0")"
mkdir -p secrets
if [ -f secrets/jwt-private.pem ]; then
  echo "Ya existe secrets/jwt-private.pem; no se sobrescribe. Borrala a mano si queres regenerarla."
  exit 0
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem
openssl rsa -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
# Docker Compose (sin Swarm) monta los secrets como bind mounts con los permisos del host,
# y el contenedor corre con un usuario sin privilegios (uid 10001): necesita permiso de lectura.
# En produccion la clave se entregaria con un gestor de secretos, no como archivo local.
chmod 644 secrets/jwt-private.pem secrets/jwt-public.pem
echo "Claves generadas en $(pwd)/secrets"
