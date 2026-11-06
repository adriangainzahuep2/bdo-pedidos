#!/usr/bin/env bash
# =============================================================================
# Verificacion del CI/CD de punta a punta: Jenkins + SonarQube + Gitea.
#
#   ./scripts/verificar-ci.sh
#
# Que hace, en orden:
#   1. Levanta SonarQube y Gitea y espera a que esten sanos.
#   2. Crea en Gitea el usuario y el repositorio 'config-repo' y sube el
#      contenido de config-repo/ (la configuracion que sirve el Config Server).
#   3. Crea en SonarQube el token de API y el webhook hacia Jenkins.
#   4. Levanta Jenkins (que ya deja configurado el job) y espera a que responda.
#   5. Lanza el pipeline y sigue el log hasta que termina.
#
# Variables de entorno:
#   SONAR_HOST_URL_PUBLICO   URL publica de SonarQube (por defecto :9001)
#   GITEA_URL_PUBLICO        URL publica de Gitea (por defecto :3000)
#   JENKINS_URL_PUBLICO      URL publica de Jenkins (por defecto :8091)
#   USUARIO_GITEA            usuario de Gitea (por defecto bdo)
#   CONTRASENA_GITEA         contrasena de ese usuario (por defecto bdo-ci)
# =============================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

COMPOSE=(docker compose -f docker-compose.ci.yml)
SONAR_PUBLICO="${SONAR_HOST_URL_PUBLICO:-http://localhost:9001}"
GITEA_PUBLICO="${GITEA_URL_PUBLICO:-http://localhost:3000}"
JENKINS_PUBLICO="${JENKINS_URL_PUBLICO:-http://localhost:8091}"
USUARIO_GITEA="${USUARIO_GITEA:-bdo}"
CONTRASENA_GITEA="${CONTRASENA_GITEA:-bdo-ci}"
ADMIN_SONAR="${ADMIN_SONAR:-admin}"
CONTRASENA_SONAR="${CONTRASENA_SONAR:-admin}"
JENKINS_USUARIO="${JENKINS_USUARIO:-admin}"
JENKINS_CLAVE="${JENKINS_CLAVE:-admin}"

log()  { printf '\033[1;34m[ CI ]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fallo(){ printf '\033[1;31m[ !! ]\033[0m %s\n' "$*" >&2; }

esperar() {  # esperar <url> <descripcion> <intentos>
  local url="$1" descripcion="$2" intentos="${3:-90}" i
  for ((i = 1; i <= intentos; i++)); do
    if curl -sf -m 5 "$url" >/dev/null 2>&1; then
      ok "$descripcion responde ($url)"
      return 0
    fi
    sleep 2
  done
  fallo "$descripcion NO responde ($url)"
  return 1
}

# El socket de Docker pertenece a root:docker. El usuario jenkins del contenedor
# necesita ese grupo para poder construir imagenes.
export DOCKER_GID="$(stat -c '%g' /var/run/docker.sock 2>/dev/null || echo 983)"
log "GID del socket de Docker: ${DOCKER_GID}"

# ---------------------------------------------------------------------------
# 1. SonarQube y Gitea
# ---------------------------------------------------------------------------
log "Levantando SonarQube y Gitea..."
"${COMPOSE[@]}" up -d sonarqube gitea
log "SonarQube tarda en arrancar (Elasticsearch). Esperando..."
esperar "${SONAR_PUBLICO}/api/system/status" "SonarQube" 120
esperar "${GITEA_PUBLICO}/api/healthz" "Gitea" 60

# Elasticsearch bloquea sus indices en solo lectura cuando el disco del host
# supera el 95 % de uso (watermark flood-stage) y entonces el analisis falla
# con "cluster_block_exception ... disk usage exceeded flood-stage watermark".
# El ajuste va en el cluster de Elasticsearch (no es una propiedad del sistema
# de la JVM, asi que -Des.disk.threshold_enabled=false NO sirve).
log "Desactivando el umbral de disco de Elasticsearch..."
ID_SONAR=$("${COMPOSE[@]}" ps -q sonarqube)
"${COMPOSE[@]}" exec -T sonarqube sh -c \
  'wget -qO- --header="Content-Type: application/json" --method=PUT \
    --body-data="{\"persistent\":{\"cluster.routing.allocation.disk.threshold_enabled\":false}}" \
    http://localhost:9001/_cluster/settings' >/dev/null 2>&1 \
  && ok "Umbral de disco de Elasticsearch desactivado" \
  || log "No se pudo cambiar el ajuste (sigue con el valor por defecto)"
[ -n "$ID_SONAR" ] || true

# ---------------------------------------------------------------------------
# 2. Gitea: usuario, repositorio de configuracion y contenido
# ---------------------------------------------------------------------------
log "Creando el usuario de Gitea '${USUARIO_GITEA}'..."
# El comando de administracion de Gitea no se puede ejecutar como root.
"${COMPOSE[@]}" exec -T -u git gitea gitea admin user create \
  --admin --username "$USUARIO_GITEA" --password "$CONTRASENA_GITEA" \
  --email "${USUARIO_GITEA}@bdo.example" --must-change-password=false >/dev/null 2>&1 \
  && ok "Usuario '${USUARIO_GITEA}' creado" \
  || log "El usuario '${USUARIO_GITEA}' ya existia"

log "Creando el repositorio 'config-repo' en Gitea..."
HTTP_REPO=$(curl -s -o /dev/null -w '%{http_code}' -u "${USUARIO_GITEA}:${CONTRASENA_GITEA}" \
  -X POST "${GITEA_PUBLICO}/api/v1/user/repos" \
  -H 'Content-Type: application/json' \
  -d '{"name":"config-repo","auto_init":true,"default_branch":"main","private":false}')
if [ "$HTTP_REPO" = "201" ] || [ "$HTTP_REPO" = "409" ]; then
  ok "Repositorio 'config-repo' disponible (HTTP ${HTTP_REPO})"
else
  fallo "No se pudo crear el repositorio (HTTP ${HTTP_REPO})"; exit 1
fi

log "Subiendo el contenido de config-repo/ a Gitea..."
CLONE="$(mktemp -d)/config-repo"
if git clone -q "http://${USUARIO_GITEA}:${CONTRASENA_GITEA}@localhost:3000/${USUARIO_GITEA}/config-repo.git" "$CLONE" 2>/dev/null; then
  cp -f config-repo/*.yml "$CLONE"/ 2>/dev/null || true
  git -C "$CLONE" add -A
  if git -C "$CLONE" diff --cached --quiet; then
    log "El repositorio de Gitea ya tiene el mismo contenido."
  else
    git -C "$CLONE" -c user.email=ci@bdo.example -c user.name="CI BDO" \
      commit -q -m "config: configuracion compartida y por servicio" && \
    git -C "$CLONE" push -q origin main && ok "config-repo actualizado en Gitea"
  fi
else
  fallo "No se pudo clonar 'config-repo' desde Gitea"; exit 1
fi

# ---------------------------------------------------------------------------
# 3. SonarQube: token de API y webhook hacia Jenkins
# ---------------------------------------------------------------------------
log "Creando el token de API de SonarQube..."
# SonarQube 9.5+ cambio el servicio de tokens: /api/user_tokens/generate.
# Se prueban las dos rutas por si la version del servidor es anterior.
TOKEN_SONAR=""
for RUTA in "api/user_tokens/generate" "api/users/tokens"; do
  TOKEN_SONAR=$(curl -s -u "${ADMIN_SONAR}:${CONTRASENA_SONAR}" -X POST \
    "${SONAR_PUBLICO}/${RUTA}?name=jenkins-ci&type=USER_TOKEN" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || true)
  [ -n "$TOKEN_SONAR" ] && break
done

if [ -z "$TOKEN_SONAR" ]; then
  fallo "No se pudo crear el token de API de SonarQube"; exit 1
fi
ok "Token de SonarQube creado"

# ---------------------------------------------------------------------------
# 4. Jenkins
# ---------------------------------------------------------------------------
log "Levantando Jenkins (la primera vez compila la imagen del agente)..."
SONAR_TOKEN="$TOKEN_SONAR" "${COMPOSE[@]}" up -d jenkins
esperar "${JENKINS_PUBLICO}/login" "Jenkins" 120

log "Registrando el webhook de SonarQube hacia Jenkins..."
# El servicio de webhooks de SonarQube 9.x es /api/webhooks/create y espera los
# parametros como formulario, no como JSON.
HTTP_HOOK=$(curl -s -o /dev/null -w '%{http_code}' -u "${ADMIN_SONAR}:${CONTRASENA_SONAR}" \
  -X POST "${SONAR_PUBLICO}/api/webhooks/create" \
  --data-urlencode "name=jenkins" \
  --data-urlencode "url=http://jenkins:8080/sonar-webhook/")
if [ "$HTTP_HOOK" = "200" ] || [ "$HTTP_HOOK" = "201" ]; then
  ok "Webhook creado: http://jenkins:8080/sonar-webhook/ (HTTP ${HTTP_HOOK})"
else
  log "No se pudo crear el webhook (HTTP ${HTTP_HOOK}); se continua."
fi

# ---------------------------------------------------------------------------
# 5. Lanzar el pipeline y seguir el log
# ---------------------------------------------------------------------------
log "Lanzando el pipeline 'bdo-pedidos'..."
# Jenkins ata el crumb a la sesion: se usa un fichero de cookies para que la
# peticion que pide el crumb y la que lanza el build compartan sesion.
COOKIES="$(mktemp)"
CRUMB=$(curl -s -c "$COOKIES" -u "${JENKINS_USUARIO}:${JENKINS_CLAVE}" \
  "${JENKINS_PUBLICO}/crumbIssuer/api/json" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['crumbRequestField']+':'+d['crumb'])" 2>/dev/null || true)
HTTP_BUILD=$(curl -s -o /dev/null -w '%{http_code}' -c "$COOKIES" -b "$COOKIES" \
  -u "${JENKINS_USUARIO}:${JENKINS_CLAVE}" -H "$CRUMB" -X POST \
  "${JENKINS_PUBLICO}/job/bdo-pedidos/build" || true)
if [ "$HTTP_BUILD" != "201" ] && [ "$HTTP_BUILD" != "302" ]; then
  fallo "No se pudo lanzar el pipeline (HTTP ${HTTP_BUILD})"; exit 1
fi
ok "Pipeline lanzado (HTTP ${HTTP_BUILD})"

log "Siguiendo el log del pipeline..."
NUMERO=""
for i in $(seq 1 60); do
  sleep 3
  CANDIDATO=$(curl -s "${JENKINS_PUBLICO}/job/bdo-pedidos/lastBuild/api/json?tree=number" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('number',''))" 2>/dev/null || true)
  if [ -n "$CANDIDATO" ] && [ "$CANDIDATO" != "$NUMERO" ]; then
    NUMERO="$CANDIDATO"
    log "Build #${NUMERO} en marcha"
    break
  fi
done
[ -z "$NUMERO" ] && { fallo "No se ve ninguna build nueva"; exit 1; }

RESULTADO=""
while true; do
  sleep 10
  ESTADO=$(curl -s "${JENKINS_PUBLICO}/job/bdo-pedidos/${NUMERO}/api/json?tree=building,result" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(('CORRIENDO' if d.get('building') else (d.get('result') or '?')))" 2>/dev/null || echo "?")
  case "$ESTADO" in
    CORRIENDO) printf '.' ;;
    *)
      RESULTADO="$ESTADO"
      echo
      break
      ;;
  esac
done

log "Resultado de la build #${NUMERO}: ${RESULTADO}"
echo
echo "--- ultimas lineas del log ---"
curl -s "${JENKINS_PUBLICO}/job/bdo-pedidos/${NUMERO}/consoleText" | tail -60
echo
echo "UI de Jenkins:   ${JENKINS_PUBLICO}/job/bdo-pedidos/${NUMERO}/console"
echo "UI de SonarQube: ${SONAR_PUBLICO}/dashboard?id=bdo-pedidos"
echo "UI de Gitea:      ${GITEA_PUBLICO}/${USUARIO_GITEA}/config-repo"
[ "$RESULTADO" = "SUCCESS" ] || exit 1
