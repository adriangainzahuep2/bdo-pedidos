#!/usr/bin/env bash
#
# Verificacion de extremo a extremo de toda la arquitectura.
#
# Levanta el stack completo (registro de servicios, servidor de autorizacion, gateway,
# microservicio de clientes, microservicio de pedidos y proveedor logistico simulado),
# ejecuta los flujos del enunciado a traves del API Gateway y comprueba los resultados.
#
# Uso:  ./scripts/verificar-e2e.sh                 # bases de datos H2 en memoria
#       E2E_BD=postgres ./scripts/verificar-e2e.sh  # PostgreSQL real y desechable (initdb)
#
set -uo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

LOG_DIR="$RAIZ/build/e2e"
mkdir -p "$LOG_DIR"

PUERTO_PROVEEDOR=8090
PUERTO_API=8081
PUERTO_CLIENTE=8082
PUERTO_GATEWAY=8080
PUERTO_AUTH=9000
PUERTO_REGISTRY=8761

SECRETO_GATEWAY="secreto-gateway-$(date +%s)"
PASSWORD_USUARIO="password-demo-$(date +%s)"
TOKEN_PROVEEDOR="token-proveedor-$(date +%s)"

PIDS=()
FALLOS=0

log()  { printf '\033[1;34m[ E2E ]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fallo(){ printf '\033[1;31m[FAIL]\033[0m %s\n' "$*"; FALLOS=$((FALLOS+1)); }

PG_DIR=""
PG_BIN_DIR=""

limpiar() {
  log "Deteniendo los servicios..."
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null
  done
  sleep 2
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid:-}" ] && kill -9 "$pid" 2>/dev/null
  done
  if [ -n "$PG_DIR" ]; then
    "$PG_BIN_DIR/pg_ctl" -D "$PG_DIR/datos" -m fast stop >/dev/null 2>&1
    rm -rf "$PG_DIR"
  fi
  log "Listo."
}
trap limpiar EXIT

esperar_registro() {
  local servicio="$1" intentos=90 respuesta
  while [ $intentos -gt 0 ]; do
    # La respuesta se captura antes de filtrarla: con "curl | grep -q", grep termina al
    # encontrar la coincidencia, curl recibe SIGPIPE y "set -o pipefail" convierte la
    # espera en un falso negativo.
    respuesta="$(curl -sf "http://localhost:$PUERTO_REGISTRY/eureka/apps/$servicio" 2>/dev/null || true)"
    if echo "$respuesta" | grep -q "<instance>"; then
      ok "El servicio $servicio esta registrado en Eureka"
      return 0
    fi
    sleep 2
    intentos=$((intentos-1))
  done
  fallo "El servicio $servicio no aparece en el registro de Eureka"
  return 1
}

# Espera a que un servicio responda. La ventana es holgada a proposito (3 minutos):
# el arranque de un servicio Spring Boot con registro en Eureka tarda 10-15 s en una
# maquina descargada, pero puede pasar de un minuto si el build anterior sigue
# compilando en paralelo y compite por la CPU. Un timeout corto aqui haria fallar la
# verificacion por saturacion de la maquina y no por un defecto del sistema.
esperar_salud() {
  local url="$1" nombre="$2" intentos=180 inicio
  inicio=$(date +%s)
  while [ $intentos -gt 0 ]; do
    if curl -sf "$url" >/dev/null 2>&1; then
      ok "$nombre responde en $url (en $(( $(date +%s) - inicio )) s)"
      return 0
    fi
    sleep 1
    intentos=$((intentos-1))
  done
  fallo "$nombre NO responde en $url (tras $(( $(date +%s) - inicio )) s)"
  return 1
}

# ---------------------------------------------------------------------------
# 1. Compilacion
# ---------------------------------------------------------------------------
log "Compilando el proyecto (sin tests, ya ejecutados aparte)..."
if ! ./gradlew --console=plain build -x test > "$LOG_DIR/build.log" 2>&1; then
  fallo "La compilacion fallo (ver $LOG_DIR/build.log)"
  tail -30 "$LOG_DIR/build.log"
  exit 1
fi
ok "Compilacion correcta"

# ---------------------------------------------------------------------------
# 1b. Base de datos: H2 en memoria (por defecto) o PostgreSQL real (E2E_BD=postgres)
# ---------------------------------------------------------------------------
PERFILES="eureka"
ENTORNO_BD_PEDIDOS=()
ENTORNO_BD_CLIENTES=()
if [ "${E2E_BD:-h2}" = "postgres" ]; then
  PG_BIN_DIR="${PG_BIN:-$(dirname "$(command -v initdb 2>/dev/null || ls -d /usr/lib/postgresql/*/bin/initdb 2>/dev/null | sort -V | tail -1)")}"
  if [ ! -x "$PG_BIN_DIR/initdb" ]; then
    fallo "E2E_BD=postgres necesita los binarios de PostgreSQL (initdb); define PG_BIN"
    exit 1
  fi
  PG_DIR="$(mktemp -d /tmp/bdo-e2e-pg.XXXXXX)"
  PG_PUERTO="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
  "$PG_BIN_DIR/initdb" -D "$PG_DIR/datos" -U pedidos -A trust -E UTF8 --no-locale > "$LOG_DIR/initdb.log" 2>&1
  "$PG_BIN_DIR/pg_ctl" -D "$PG_DIR/datos" -l "$LOG_DIR/postgres.log" -w \
    -o "-p $PG_PUERTO -k $PG_DIR -c listen_addresses=127.0.0.1" start > /dev/null
  for bd in pedidos clientes; do
    "$PG_BIN_DIR/psql" -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PUERTO" -U pedidos -d postgres -c "CREATE DATABASE $bd"
  done
  "$PG_BIN_DIR/psql" -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PUERTO" -U pedidos -d pedidos \
    -f "$RAIZ/pedidos-api/src/main/resources/db/schema.sql"
  "$PG_BIN_DIR/psql" -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PUERTO" -U pedidos -d clientes \
    -f "$RAIZ/pedidos-cliente/src/main/resources/db/schema.sql"
  ok "PostgreSQL real arrancado en el puerto $PG_PUERTO (bases 'pedidos' y 'clientes' con su DDL)"
  PERFILES="eureka,postgres"
  ENTORNO_BD_PEDIDOS=(SPRING_R2DBC_URL="r2dbc:postgresql://127.0.0.1:$PG_PUERTO/pedidos"
                      SPRING_R2DBC_USERNAME=pedidos SPRING_R2DBC_PASSWORD=pedidos)
  ENTORNO_BD_CLIENTES=(SPRING_R2DBC_URL="r2dbc:postgresql://127.0.0.1:$PG_PUERTO/clientes"
                       SPRING_R2DBC_USERNAME=pedidos SPRING_R2DBC_PASSWORD=pedidos)
else
  ok "Bases de datos H2 en memoria (E2E_BD=postgres para usar PostgreSQL)"
fi

# ---------------------------------------------------------------------------
# 2. Arranque de los servicios
# ---------------------------------------------------------------------------
log "Arrancando el proveedor logistico simulado..."
python3 "$RAIZ/scripts/mock-proveedor.py" --puerto $PUERTO_PROVEEDOR --token "$TOKEN_PROVEEDOR" > "$LOG_DIR/proveedor.log" 2>&1 &
PIDS+=($!)

log "Arrancando el servidor de autorizacion OAuth2..."
AUTH_USUARIO_PASSWORD="$PASSWORD_USUARIO" AUTH_CLIENT_SECRET_GATEWAY="$SECRETO_GATEWAY" \
  java -Xmx256m -jar pedidos-auth/build/libs/pedidos-auth-1.0.0.jar --server.port=$PUERTO_AUTH \
  > "$LOG_DIR/auth.log" 2>&1 &
PIDS+=($!)

log "Arrancando el servidor de descubrimiento (Eureka)..."
java -Xmx256m -jar pedidos-registry/build/libs/pedidos-registry-1.0.0.jar --server.port=$PUERTO_REGISTRY \
  > "$LOG_DIR/registry.log" 2>&1 &
PIDS+=($!)

esperar_salud "http://localhost:$PUERTO_AUTH/actuator/health" "servidor de autorizacion" || exit 1
esperar_salud "http://localhost:$PUERTO_REGISTRY/actuator/health" "registro de servicios" || exit 1

log "Arrancando el microservicio de clientes..."
env SPRING_PROFILES_ACTIVE=$PERFILES EUREKA_URL="http://localhost:$PUERTO_REGISTRY/eureka/" "${ENTORNO_BD_CLIENTES[@]}" \
  java -Xmx256m -jar pedidos-cliente/build/libs/pedidos-cliente-1.0.0.jar --server.port=$PUERTO_CLIENTE \
  > "$LOG_DIR/cliente.log" 2>&1 &
PIDS+=($!)

log "Arrancando el microservicio de pedidos..."
env SPRING_PROFILES_ACTIVE=$PERFILES EUREKA_URL="http://localhost:$PUERTO_REGISTRY/eureka/" "${ENTORNO_BD_PEDIDOS[@]}" \
  PROVEEDOR_LOGISTICO_TOKEN="$TOKEN_PROVEEDOR" \
  PROVEEDOR_LOGISTICO_URL="http://localhost:$PUERTO_PROVEEDOR/api/tarifas" \
  java -Xmx256m -jar pedidos-api/build/libs/pedidos-api-1.0.0.jar --server.port=$PUERTO_API \
  > "$LOG_DIR/api.log" 2>&1 &
PIDS+=($!)

log "Arrancando el API Gateway..."
SPRING_PROFILES_ACTIVE=eureka EUREKA_URL="http://localhost:$PUERTO_REGISTRY/eureka/" \
  java -Xmx256m -jar pedidos-gateway/build/libs/pedidos-gateway-1.0.0.jar --server.port=$PUERTO_GATEWAY \
  > "$LOG_DIR/gateway.log" 2>&1 &
PIDS+=($!)

esperar_salud "http://localhost:$PUERTO_CLIENTE/actuator/health" "microservicio de clientes" || exit 1
esperar_salud "http://localhost:$PUERTO_API/actuator/health" "microservicio de pedidos" || exit 1
esperar_salud "http://localhost:$PUERTO_GATEWAY/actuator/health" "API Gateway" || exit 1

# El registro necesita unos segundos para que el gateway vea los servicios.
log "Esperando el alta de los servicios en el registro de Eureka..."
esperar_registro PEDIDOS-API || exit 1
esperar_registro PEDIDOS-CLIENTE || exit 1
esperar_registro PEDIDOS-GATEWAY || exit 1

# ---------------------------------------------------------------------------
# 3. Token OAuth2
# ---------------------------------------------------------------------------
log "Solicitando token OAuth2 (client_credentials)..."
RESPUESTA_TOKEN=$(curl -s -u "pedidos-gateway:$SECRETO_GATEWAY" \
  -d "grant_type=client_credentials&scope=pedidos.read pedidos.write clientes.read clientes.write" \
  http://localhost:$PUERTO_AUTH/oauth2/token)
TOKEN=$(echo "$RESPUESTA_TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  fallo "No se pudo obtener el token: $RESPUESTA_TOKEN"
  exit 1
fi
ok "Token obtenido (${#TOKEN} caracteres)"

AUTORIZACION="Authorization: Bearer $TOKEN"

# Que un servicio este registrado en Eureka no significa que el gateway ya lo vea: su
# copia del registro se refresca cada pocos segundos. Se espera a que el gateway enrute
# de verdad a cada servicio (404 del servicio para un recurso inexistente, no 503).
esperar_ruta() {
  local ruta="$1" nombre="$2" intentos=60 codigo
  while [ $intentos -gt 0 ]; do
    codigo=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTORIZACION" "http://localhost:$PUERTO_GATEWAY$ruta")
    if [ "$codigo" = "404" ]; then
      ok "El gateway enruta a $nombre"
      return 0
    fi
    sleep 1
    intentos=$((intentos-1))
  done
  fallo "El gateway no enruta a $nombre (ultimo codigo: $codigo)"
  return 1
}
esperar_ruta "/api/clientes/999999999" "microservicio de clientes" || exit 1
esperar_ruta "/api/pedidos/999999999" "microservicio de pedidos" || exit 1

# Y el servicio de pedidos debe ver al de clientes (tambien a traves de Eureka): un pedido
# de un cliente inexistente debe responder 422 (cliente no encontrado), no 503.
intentos=60
while [ $intentos -gt 0 ]; do
  CODIGO=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:$PUERTO_GATEWAY/api/pedidos" \
    -H "$AUTORIZACION" -H "Content-Type: application/json" \
    -d '{"clienteId":999999999,"codigoPostalDestino":"43840","lineas":[{"producto":"X","cantidad":1,"precioUnitario":1.00,"pesoUnitarioKg":0.1}]}')
  [ "$CODIGO" = "422" ] && break
  sleep 1
  intentos=$((intentos-1))
done
if [ "$CODIGO" = "422" ]; then
  ok "El servicio de pedidos llega al de clientes (cliente inexistente -> 422)"
else
  fallo "El servicio de pedidos no llega al de clientes (ultimo codigo: $CODIGO)"
  exit 1
fi

# ---------------------------------------------------------------------------
# 4. Comprobaciones
# ---------------------------------------------------------------------------
log "Comprobacion 1: sin token, la API responde 401"
CODIGO=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PUERTO_GATEWAY/api/pedidos/1)
[ "$CODIGO" = "401" ] && ok "401 sin token" || fallo "Se esperaba 401 y se obtuvo $CODIGO"

log "Comprobacion 2: alta de cliente a traves del gateway"
CLIENTE=$(curl -s -X POST http://localhost:$PUERTO_GATEWAY/api/clientes \
  -H "$AUTORIZACION" -H "Content-Type: application/json" \
  -d '{"nombre":"Empresa Demo S.L.","email":"demo@bdo-es.example"}')
ID_CLIENTE=$(echo "$CLIENTE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
[ -n "$ID_CLIENTE" ] && ok "Cliente creado con id $ID_CLIENTE" || fallo "No se pudo crear el cliente: $CLIENTE"

log "Comprobacion 3: creacion de pedido (importe calculado por el servidor)"
PEDIDO=$(curl -s -X POST http://localhost:$PUERTO_GATEWAY/api/pedidos \
  -H "$AUTORIZACION" -H "Content-Type: application/json" \
  -d "{\"clienteId\":$ID_CLIENTE,\"codigoPostalDestino\":\"43840\",\"lineas\":[
       {\"producto\":\"Teclado mecanico\",\"cantidad\":2,\"precioUnitario\":45.9900,\"pesoUnitarioKg\":0.800},
       {\"producto\":\"Raton inalambrico\",\"cantidad\":1,\"precioUnitario\":19.5000,\"pesoUnitarioKg\":0.100}]}")
ID_PEDIDO=$(echo "$PEDIDO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
IMPORTE=$(echo "$PEDIDO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('importeProductos',''))" 2>/dev/null)
[ "$IMPORTE" = "111.48" ] && ok "Importe calculado por el servidor: $IMPORTE" \
  || fallo "Importe esperado 111.48, obtenido '$IMPORTE' (pedido: $PEDIDO)"

log "Comprobacion 4: confirmacion (coste de envio del proveedor + total)"
CONFIRMADO=$(curl -s -X POST "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO/confirmacion" \
  -H "$AUTORIZACION")
COSTE=$(echo "$CONFIRMADO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('costeEnvio',''))" 2>/dev/null)
TOTAL=$(echo "$CONFIRMADO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('importeTotal',''))" 2>/dev/null)
ESTADO=$(echo "$CONFIRMADO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('estado',''))" 2>/dev/null)
[ "$ESTADO" = "CONFIRMADO" ] && ok "Pedido confirmado, coste=$COSTE, total=$TOTAL" \
  || fallo "Confirmacion incorrecta: $CONFIRMADO"

log "Comprobacion 5: idempotencia (confirmar de nuevo no vuelve a llamar al proveedor)"
LLAMADAS_ANTES=$(curl -s http://localhost:$PUERTO_PROVEEDOR/admin/estadisticas | python3 -c "import sys,json; print(json.load(sys.stdin)['llamadas_ok'])" 2>/dev/null)
curl -s -o /dev/null -X POST "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO/confirmacion" -H "$AUTORIZACION"
LLAMADAS_DESPUES=$(curl -s http://localhost:$PUERTO_PROVEEDOR/admin/estadisticas | python3 -c "import sys,json; print(json.load(sys.stdin)['llamadas_ok'])" 2>/dev/null)
# Se exige que los dos contadores sean numeros: si el proveedor no esta accesible, los
# contadores salen vacios y la comparacion "vacio == vacio" daria la comprobacion por
# buena sin haber comprobado nada.
if [ -z "$LLAMADAS_ANTES" ] || [ -z "$LLAMADAS_DESPUES" ]; then
  fallo "No se pudo consultar el contador de llamadas al proveedor (antes='$LLAMADAS_ANTES' despues='$LLAMADAS_DESPUES')"
elif [ "$LLAMADAS_ANTES" = "$LLAMADAS_DESPUES" ]; then
  ok "La segunda confirmacion no ha vuelto a llamar al proveedor ($LLAMADAS_ANTES -> $LLAMADAS_DESPUES)"
else
  fallo "La segunda confirmacion ha vuelto a llamar al proveedor ($LLAMADAS_ANTES -> $LLAMADAS_DESPUES)"
fi

log "Comprobacion 6: dos confirmaciones simultaneas -> una sola llamada al proveedor"
# Para que la carrera sea determinista, el proveedor se reinicia con 1,5 s de latencia:
# asi la peticion perdedora observa el pedido en estado CONFIRMANDO (en curso) y recibe
# 409, en lugar de encontrarse el pedido ya CONFIRMADO (200 idempotente). Ambos finales
# son correctos; con latencia se comprueba el caso interesante de forma reproducible.
kill $(pgrep -f "mock-proveedor.py --puerto $PUERTO_PROVEEDOR" | head -1) 2>/dev/null
sleep 1
python3 "$RAIZ/scripts/mock-proveedor.py" --puerto $PUERTO_PROVEEDOR --token "$TOKEN_PROVEEDOR" --latencia 1.5 \
  > "$LOG_DIR/proveedor-lento.log" 2>&1 &
PIDS+=($!)
sleep 2
PEDIDO2=$(curl -s -X POST http://localhost:$PUERTO_GATEWAY/api/pedidos \
  -H "$AUTORIZACION" -H "Content-Type: application/json" \
  -d "{\"clienteId\":$ID_CLIENTE,\"codigoPostalDestino\":\"43840\",\"lineas\":[
       {\"producto\":\"Suscripcion mensual\",\"cantidad\":3,\"precioUnitario\":9.9900,\"pesoUnitarioKg\":0.000}]}")
ID_PEDIDO2=$(echo "$PEDIDO2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
LLAMADAS_ANTES=$(curl -s http://localhost:$PUERTO_PROVEEDOR/admin/estadisticas | python3 -c "import sys,json; print(json.load(sys.stdin)['llamadas_ok'])" 2>/dev/null)
curl -s -o "$LOG_DIR/concurrente1.json" -w "%{http_code}" -X POST \
  "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO2/confirmacion" -H "$AUTORIZACION" > "$LOG_DIR/estado1.txt" &
PID1=$!
curl -s -o "$LOG_DIR/concurrente2.json" -w "%{http_code}" -X POST \
  "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO2/confirmacion" -H "$AUTORIZACION" > "$LOG_DIR/estado2.txt" &
PID2=$!
wait $PID1 $PID2
E1=$(cat "$LOG_DIR/estado1.txt"); E2=$(cat "$LOG_DIR/estado2.txt")
LLAMADAS_DESPUES=$(curl -s http://localhost:$PUERTO_PROVEEDOR/admin/estadisticas | python3 -c "import sys,json; print(json.load(sys.stdin)['llamadas_ok'])" 2>/dev/null)
CODIGOS=$(echo "$E1 $E2" | tr ' ' '\n' | sort | tr '\n' ' ')
if [ "$CODIGOS" = "200 409 " ] || [ "$CODIGOS" = "409 200 " ]; then
  ok "Una confirmacion devuelve 200 y la otra 409 ($CODIGOS)"
else
  fallo "Se esperaba 200 y 409, obtenido: $CODIGOS"
fi
LLAMADAS_NUEVAS=$((LLAMADAS_DESPUES - LLAMADAS_ANTES))
if [ "$LLAMADAS_NUEVAS" = "1" ]; then
  ok "Solo UNA llamada nueva al proveedor ($LLAMADAS_ANTES -> $LLAMADAS_DESPUES)"
else
  fallo "Se esperaba una sola llamada nueva al proveedor, hubo $LLAMADAS_NUEVAS ($LLAMADAS_ANTES -> $LLAMADAS_DESPUES)"
fi
# Y el pedido ha quedado coherente: confirmado, con un unico coste de envio.
PEDIDO2_FINAL=$(curl -s "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO2" -H "$AUTORIZACION")
ESTADO2=$(echo "$PEDIDO2_FINAL" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('estado',''))" 2>/dev/null)
COSTE2=$(echo "$PEDIDO2_FINAL" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('costeEnvio',''))" 2>/dev/null)
if [ "$ESTADO2" = "CONFIRMADO" ] && [ -n "$COSTE2" ] && [ "$COSTE2" != "None" ]; then
  ok "El pedido queda CONFIRMADO con un unico coste de envio ($COSTE2)"
else
  fallo "El pedido no ha quedado coherente: estado=$ESTADO2 costeEnvio=$COSTE2"
fi

log "Comprobacion 7: el proveedor falla -> el pedido queda PENDIENTE y la API responde 502"
kill $(pgrep -f "mock-proveedor.py --puerto $PUERTO_PROVEEDOR" | head -1) 2>/dev/null
sleep 1
python3 "$RAIZ/scripts/mock-proveedor.py" --puerto $PUERTO_PROVEEDOR --token "$TOKEN_PROVEEDOR" --fallo 503 --proporcion 1.0 \
  > "$LOG_DIR/proveedor-error.log" 2>&1 &
PIDS+=($!)
sleep 2
PEDIDO3=$(curl -s -X POST http://localhost:$PUERTO_GATEWAY/api/pedidos \
  -H "$AUTORIZACION" -H "Content-Type: application/json" \
  -d "{\"clienteId\":$ID_CLIENTE,\"codigoPostalDestino\":\"43840\",\"lineas\":[
       {\"producto\":\"Producto sin stock\",\"cantidad\":1,\"precioUnitario\":5.0000,\"pesoUnitarioKg\":1.000}]}")
ID_PEDIDO3=$(echo "$PEDIDO3" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
CODIGO_FALLO=$(curl -s -o "$LOG_DIR/error-proveedor.json" -w "%{http_code}" -X POST \
  "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO3/confirmacion" -H "$AUTORIZACION")
[ "$CODIGO_FALLO" = "502" ] && ok "El proveedor devuelve 503 y la API responde 502" \
  || fallo "Se esperaba 502 y se obtuvo $CODIGO_FALLO"
ESTADO3=$(curl -s "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO3" -H "$AUTORIZACION" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('estado',''))" 2>/dev/null)
[ "$ESTADO3" = "PENDIENTE" ] && ok "El pedido sigue en PENDIENTE (reintentable)" \
  || fallo "Se esperaba PENDIENTE y se obtuvo $ESTADO3"

log "Comprobacion 8: la consola web (HTML, CSS y JavaScript) se sirve a traves del gateway"
HTML_CONSOLA=$(curl -s http://localhost:$PUERTO_GATEWAY/)
CODIGOS_ESTATICOS=""
for fichero in index.html app.js pkce.js styles.css; do
  CODIGOS_ESTATICOS="$CODIGOS_ESTATICOS$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PUERTO_GATEWAY/$fichero") "
done
if echo "$HTML_CONSOLA" | grep -q "Consola de pedidos" && [ "$CODIGOS_ESTATICOS" = "200 200 200 200 " ]; then
  ok "La consola se sirve sin token en http://localhost:$PUERTO_GATEWAY/ (index.html, app.js, pkce.js, styles.css)"
else
  fallo "La consola no se sirve por el gateway (codigos: $CODIGOS_ESTATICOS)"
fi

log "Comprobacion 9: inicio de sesion de la consola (authorization_code + PKCE, como hace app.js)"
# Mismo flujo que el navegador: formulario de login (con CSRF), /oauth2/authorize con el
# desafio S256, y canje del codigo con el verificador desde el origen de la consola (CORS).
COOKIES="$LOG_DIR/cookies-auth.txt"; rm -f "$COOKIES"
VERIFICADOR="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
DESAFIO="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
REDIRECCION="http://localhost:$PUERTO_GATEWAY/index.html"
CSRF=$(curl -s -c "$COOKIES" http://localhost:$PUERTO_AUTH/login | sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' | head -1)
curl -s -o /dev/null -b "$COOKIES" -c "$COOKIES" -X POST http://localhost:$PUERTO_AUTH/login \
  --data-urlencode "username=demo" --data-urlencode "password=$PASSWORD_USUARIO" --data-urlencode "_csrf=$CSRF"
URL_AUTORIZACION="http://localhost:$PUERTO_AUTH/oauth2/authorize?response_type=code&client_id=consola-web"
URL_AUTORIZACION="$URL_AUTORIZACION&redirect_uri=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$REDIRECCION")"
URL_AUTORIZACION="$URL_AUTORIZACION&scope=pedidos.read+pedidos.write+clientes.read+clientes.write&state=e2e"
URL_AUTORIZACION="$URL_AUTORIZACION&code_challenge=$DESAFIO&code_challenge_method=S256"
VUELTA=$(curl -s -o /dev/null -b "$COOKIES" -w "%{redirect_url}" "$URL_AUTORIZACION")
CODIGO_AUTORIZACION=$(python3 -c "import urllib.parse,sys;q=urllib.parse.urlparse(sys.argv[1]).query;print(urllib.parse.parse_qs(q).get('code',[''])[0])" "$VUELTA")
if [ -n "$CODIGO_AUTORIZACION" ] && [ "${VUELTA%%\?*}" = "$REDIRECCION" ]; then
  ok "Tras el login, /oauth2/authorize devuelve un codigo a la consola"
else
  fallo "No se obtuvo el codigo de autorizacion (vuelta: $VUELTA)"
fi
RESPUESTA_CANJE=$(curl -s -D "$LOG_DIR/cabeceras-canje.txt" -X POST http://localhost:$PUERTO_AUTH/oauth2/token \
  -H "Origin: http://localhost:$PUERTO_GATEWAY" \
  --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODIGO_AUTORIZACION" \
  --data-urlencode "redirect_uri=$REDIRECCION" --data-urlencode "client_id=consola-web" \
  --data-urlencode "code_verifier=$VERIFICADOR")
TOKEN_USUARIO=$(echo "$RESPUESTA_CANJE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
if [ -n "$TOKEN_USUARIO" ] && grep -qi "^access-control-allow-origin: http://localhost:$PUERTO_GATEWAY" "$LOG_DIR/cabeceras-canje.txt"; then
  ok "El codigo se canjea con el verificador PKCE, sin secreto y con CORS para la consola"
else
  fallo "El canje del codigo fallo: $RESPUESTA_CANJE"
fi
CODIGO_USUARIO=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PUERTO_GATEWAY/api/pedidos/$ID_PEDIDO" \
  -H "Authorization: Bearer $TOKEN_USUARIO")
[ "$CODIGO_USUARIO" = "200" ] && ok "Con el token del usuario la consola consulta pedidos a traves del gateway" \
  || fallo "Con el token del usuario se esperaba 200 y se obtuvo $CODIGO_USUARIO"

# ---------------------------------------------------------------------------
# 8. Resultado
# ---------------------------------------------------------------------------
echo
if [ "$FALLOS" -eq 0 ]; then
  printf '\033[1;32m========== VERIFICACION DE EXTREMO A EXTREMO CORRECTA ==========\033[0m\n'
else
  printf '\033[1;31m========== %s COMPROBACIONES FALLIDAS ==========\033[0m\n' "$FALLOS"
fi
exit "$FALLOS"
