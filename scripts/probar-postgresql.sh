#!/usr/bin/env bash
#
# Prueba el esquema PostgreSQL contra un PostgreSQL REAL y desechable.
#
#   ./scripts/probar-postgresql.sh
#
# 1. Crea un cluster temporal (initdb) en un directorio temporal y lo arranca en un
#    puerto libre, sin tocar ningun PostgreSQL ya instalado.
# 2. Aplica db/postgresql/esquema.sql, carga db/postgresql/datos-prueba.sql (10 filas por
#    tabla) y ejecuta db/postgresql/verificacion.sql.
# 3. Aplica los DDL de cada microservicio en bases de datos separadas (como en docker
#    compose) y comprueba que definen exactamente las mismas columnas y restricciones que
#    el esquema consolidado.
# 4. Para y borra el cluster.
#
# Requisitos: binarios de PostgreSQL (initdb, pg_ctl, psql). Se buscan en el PATH y en
# /usr/lib/postgresql/<version>/bin. Se puede forzar con PG_BIN=/ruta/bin.
#
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${PG_BIN:-}" ]; then
  if command -v initdb >/dev/null 2>&1; then
    PG_BIN="$(dirname "$(command -v initdb)")"
  else
    PG_BIN="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
if [ -z "$PG_BIN" ] || [ ! -x "$PG_BIN/initdb" ]; then
  echo "No se encuentran los binarios de PostgreSQL (initdb). Define PG_BIN=/ruta/a/bin" >&2
  exit 2
fi

DIR="$(mktemp -d "${TMPDIR:-/tmp}/bdo-pg.XXXXXX")"
PUERTO="${PG_PUERTO:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')}"
USUARIO="$(id -un)"

# La ruta de un socket Unix no puede pasar de ~107 caracteres: con un TMPDIR largo el
# socket va a un directorio corto propio.
SOCKET="$DIR"
if [ "${#DIR}" -gt 80 ]; then
  SOCKET="$(mktemp -d /tmp/bdopg.XXXXXX)"
fi

parar() {
  "$PG_BIN/pg_ctl" -D "$DIR/datos" -m fast stop >/dev/null 2>&1 || true
  rm -rf "$DIR"
  [ "$SOCKET" != "$DIR" ] && rm -rf "$SOCKET"
  return 0
}
trap parar EXIT

echo "[pg] PostgreSQL: $("$PG_BIN/postgres" --version)"
"$PG_BIN/initdb" -D "$DIR/datos" -U "$USUARIO" -A trust -E UTF8 --no-locale >"$DIR/initdb.log" 2>&1
if ! "$PG_BIN/pg_ctl" -D "$DIR/datos" -l "$DIR/servidor.log" -w \
  -o "-p $PUERTO -k $SOCKET -c listen_addresses=''" start >/dev/null; then
  echo "[pg] no se pudo arrancar el cluster temporal:" >&2
  cat "$DIR/servidor.log" >&2
  exit 2
fi
echo "[pg] cluster temporal arrancado (socket en $SOCKET, puerto $PUERTO)"

psql_() { "$PG_BIN/psql" -X -q -v ON_ERROR_STOP=1 -h "$SOCKET" -p "$PUERTO" -U "$USUARIO" "$@"; }

# --- 1. Esquema consolidado + datos + verificacion -------------------------------
psql_ -d postgres -c "CREATE DATABASE bdo_pedidos"
psql_ -d bdo_pedidos -f "$RAIZ/db/postgresql/esquema.sql"
echo "[pg] esquema.sql aplicado"
psql_ -d bdo_pedidos -f "$RAIZ/db/postgresql/datos-prueba.sql" >/dev/null
echo "[pg] datos-prueba.sql cargado"
psql_ -d bdo_pedidos -f "$RAIZ/db/postgresql/verificacion.sql" 2>&1 | sed 's/^psql:[^ ]* NOTICE:  /  /; s/^NOTICE:  /  /'

# --- 2. Los DDL de los servicios equivalen al consolidado -------------------------
psql_ -d postgres -c "CREATE DATABASE pedidos" -c "CREATE DATABASE clientes"
psql_ -d pedidos -f "$RAIZ/pedidos-api/src/main/resources/db/schema.sql"
psql_ -d clientes -f "$RAIZ/pedidos-cliente/src/main/resources/db/schema.sql"
# Los datos de demostracion del servicio de pedidos tambien deben ser validos en PostgreSQL.
psql_ -d pedidos -f "$RAIZ/pedidos-api/src/main/resources/db/data.sql" >/dev/null
echo "[pg] DDL de los servicios (y data.sql de demostracion) aplicados en bases separadas"

DEFINICION="
  SELECT 'columna ' || table_name || '.' || column_name || ' ' || data_type
         || coalesce('(' || character_maximum_length || ')', '')
         || coalesce('(' || numeric_precision || ',' || numeric_scale || ')', '')
         || ' nulos=' || is_nullable || ' defecto=' || coalesce(column_default, '-')
    FROM information_schema.columns WHERE table_schema = 'public'
  UNION ALL
  SELECT 'restriccion ' || conrelid::regclass || '.' || conname || ' ' || pg_get_constraintdef(oid)
    FROM pg_constraint WHERE connamespace = 'public'::regnamespace
  ORDER BY 1"

psql_ -d bdo_pedidos -At -c "$DEFINICION" > "$DIR/consolidado.txt"
{ psql_ -d pedidos -At -c "$DEFINICION"; psql_ -d clientes -At -c "$DEFINICION"; } | sort > "$DIR/servicios.txt"
sort -o "$DIR/consolidado.txt" "$DIR/consolidado.txt"

if diff -u "$DIR/consolidado.txt" "$DIR/servicios.txt"; then
  echo "[pg] OK  el esquema consolidado y los DDL de los servicios son identicos ($(wc -l < "$DIR/consolidado.txt") columnas y restricciones)"
else
  echo "[pg] FALLO: db/postgresql/esquema.sql y los DDL de los servicios no coinciden" >&2
  exit 1
fi

echo "[pg] ========== PRUEBA DE POSTGRESQL CORRECTA =========="
