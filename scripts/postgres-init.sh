#!/bin/sh
# =============================================================================
# Inicializacion de PostgreSQL para docker compose (solo con el volumen vacio).
#
# Cada microservicio es dueno de su base de datos:
#   * "pedidos"  (creada por POSTGRES_DB)  -> DDL del servicio de pedidos
#   * "clientes" (creada aqui)             -> DDL del servicio de clientes
# =============================================================================
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /esquemas/pedidos.sql
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c "CREATE DATABASE clientes"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname clientes -f /esquemas/clientes.sql
