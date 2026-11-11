# Arrancar la aplicación y los tests

Guía completa: requisitos, tests, arranque en local y con Docker Compose, y la consola web. El
resumen está en el [README](../README.md); la plataforma (Zipkin, Prometheus, RabbitMQ), en
[PLATAFORMA.md](PLATAFORMA.md) §9.

## Requisitos

* **JDK 17** (el enunciado pide Java 17). El build compila con `--release 17`, así que la API
  usada está comprobada contra Java 17 aunque la JVM que ejecuta Gradle sea más moderna.
* No hace falta instalar Gradle: el repositorio incluye el *wrapper* (`./gradlew`, Gradle 8.10.2).
* Python 3 para el proveedor simulado y los scripts. Node.js 18+ para los tests de JavaScript de
  la consola web. PostgreSQL 13+ (binarios `initdb`/`psql`) solo para las pruebas sobre
  PostgreSQL. Docker solo para `docker compose`.

## Tests

```bash
./gradlew build                     # compila, 159 tests (unitarios + integracion) y cobertura JaCoCo
./gradlew :pedidos-dominio:test     # solo el dominio (Java puro, segundos)
./gradlew :pedidos-api:testJavaScript   # tests de JavaScript de la consola (node --test)
./scripts/probar-postgresql.sh      # esquema PostgreSQL + 10 filas por tabla + verificacion (PostgreSQL real)
./scripts/verificar-e2e.sh          # stack completo, flujos del enunciado a traves del gateway (H2)
E2E_BD=postgres ./scripts/verificar-e2e.sh   # lo mismo sobre PostgreSQL real
```

Los dos tests que pide el enunciado:

* **Test unitario del cálculo del total**: `pedidos-dominio/.../CalculadoraTotalPedidoTest`.
* **Test de integración con error o timeout del proveedor** (WireMock):
  `ProveedorTarifasClientReactivoTest` (503 reintentado, timeout no reintentado, respuestas
  inválidas, circuito) y, a nivel de API, `PedidoControllerTest.falloDelProveedor` (502 y el pedido
  vuelve a `PENDIENTE`) y `PedidoControllerTimeoutTest` (504).

## Arrancar (local)

```bash
./gradlew build -x test
python3 scripts/mock-proveedor.py --puerto 8090 --token mi-token          # proveedor simulado

AUTH_CLIENT_SECRET_GATEWAY=secreto AUTH_USUARIO_PASSWORD=demo \
  java -jar pedidos-auth/build/libs/pedidos-auth-1.0.0.jar              # :9000 OAuth2/JWT
java -jar pedidos-registry/build/libs/pedidos-registry-1.0.0.jar        # :8761 Eureka
SPRING_PROFILES_ACTIVE=eureka \
  java -jar pedidos-cliente/build/libs/pedidos-cliente-1.0.0.jar        # :8082 clientes
SPRING_PROFILES_ACTIVE=eureka PROVEEDOR_LOGISTICO_TOKEN=mi-token \
  PROVEEDOR_LOGISTICO_URL=http://localhost:8090/api/tarifas \
  java -jar pedidos-api/build/libs/pedidos-api-1.0.0.jar                # :8081 pedidos
SPRING_PROFILES_ACTIVE=eureka \
  java -jar pedidos-gateway/build/libs/pedidos-gateway-1.0.0.jar        # :8080 gateway
```

Obtener un token y crear un pedido:

```bash
TOKEN=$(curl -s -u pedidos-gateway:secreto \
  -d "grant_type=client_credentials&scope=pedidos.read pedidos.write clientes.read clientes.write" \
  http://localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

curl -s -X POST http://localhost:8080/api/clientes -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"nombre":"Empresa Demo S.L.","email":"demo@example.com"}'

curl -s -X POST http://localhost:8080/api/pedidos -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"clienteId":1,"codigoPostalDestino":"43840","lineas":[
   {"producto":"Teclado","cantidad":2,"precioUnitario":45.99,"pesoUnitarioKg":0.8}]}'

curl -s -X POST http://localhost:8080/api/pedidos/1/confirmacion -H "Authorization: Bearer $TOKEN"
```

`documentacion/pedidos.http` tiene todas las peticiones listas para IntelliJ / VS Code.

## Arrancar con Docker Compose

Con Docker no hace falta instalar Java ni PostgreSQL: el `Dockerfile` compila el módulo dentro
de la imagen (`eclipse-temurin:17`) y `docker-compose.yml` levanta los seis servicios, PostgreSQL
y el proveedor logístico simulado, con la red y las dependencias ya resueltas.

**Requisitos**: Docker Engine y Docker Compose v2 (`docker compose version`).

**1. Preparar los secretos.** No están en el repositorio: se copian de la plantilla y se leen de
`.env` (ignorado por git).

```bash
cp .env.example .env
```

Hay que rellenar estos valores de `.env`:

| Variable | Para qué sirve | Si falta |
|---|---|---|
| `AUTH_CLIENT_SECRET_GATEWAY` | Secreto del cliente OAuth2 `pedidos-gateway` (`client_credentials`) | El servidor de autorización **no arranca** («Falta el secreto del cliente OAuth `pedidos-gateway`») |
| `AUTH_USUARIO_PASSWORD` | Contraseña del usuario de la consola web (`authorization_code` + PKCE) | El servidor de autorización **no arranca** |
| `PROVEEDOR_LOGISTICO_TOKEN` | Token que exige el proveedor logístico | El servicio de pedidos **no arranca** |
| `POSTGRES_PASSWORD` | Contraseña de PostgreSQL | Usa la de por defecto (`pedidos`) |
| `CONFIG_ENCRYPT_KEY` | Clave simétrica de cifrado JCE del Config Server | Solo hace falta si se cifra algo en `config-repo/` |

`PROVEEDOR_LOGISTICO_TOKEN` **no es un valor cualquiera**: el compose se lo pasa al proveedor
simulado y al servicio de pedidos, así que ambos usan el mismo (si no coinciden, el proveedor
responde `401` y el pedido no se puede confirmar). Para una prueba local vale cualquier cadena
no vacía, pero en un entorno real es un secreto del cliente.

**2. Levantar el stack.**

```bash
docker compose up --build            # primera vez: compila las imágenes y arranca
docker compose up -d                 # en segundo plano
docker compose logs -f pedidos-api   # seguir el arranque de un servicio
docker compose ps                    # estado y salud de cada servicio
```

El arranque está encadenado por dependencias: PostgreSQL y el registro de servicios exponen
*healthcheck* y el resto no arranca hasta que están sanos.

> Los *healthcheck* consultan también el espacio en disco del contenedor (marca `DOWN` si hay menos
> de 10 MB libres): con el disco lleno, el stack se queda esperando aunque el código esté bien.

**3. Comprobar que responde.** La consola web está en **<http://localhost:8080/>** (la publica el
gateway) y el resto de servicios, en estos puertos:

| Servicio | Puerto en la máquina | Qué es |
|---|---|---|
| `pedidos-gateway` | 8080 | Gateway y consola web |
| `pedidos-api` | 8081 | API de pedidos |
| `pedidos-cliente` | 8082 | API de clientes |
| `pedidos-auth` | 9000 | Servidor de autorización OAuth2 / JWT |
| `pedidos-registry` | 8761 | Eureka |
| `pedidos-config` | 8888 | Spring Cloud Config Server |
| `postgres` | 55432 | PostgreSQL 16 con las bases `pedidos` y `clientes` |
| `proveedor-logistico` | 8090 | Proveedor logístico simulado |
| `rabbitmq` | 5672 · 15672 | Broker de eventos (`PedidoConfirmado`) y su consola (guest/guest) |
| `zipkin` | 9411 | Trazas distribuidas |
| `prometheus` | 9090 | Métricas de los servicios |

```bash
curl -s http://localhost:8080/actuator/health   # {"status":"UP"}
```

**4. Probar la API.** Igual que en local, pero con el secreto de `.env`:

```bash
SECRETO=$(sed -n 's/^AUTH_CLIENT_SECRET_GATEWAY=//p' .env)

TOKEN=$(curl -s -u "pedidos-gateway:$SECRETO" \
  -d "grant_type=client_credentials&scope=pedidos.read pedidos.write clientes.read clientes.write" \
  http://localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# 1. Alta de cliente (el pedido necesita uno: si no existe, la API responde 422)
curl -s -X POST http://localhost:8080/api/clientes -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"nombre":"Empresa Demo S.L.","email":"demo@example.com"}'

# 2. Pedido: el importe lo calcula el servidor, no se envia
curl -s -X POST http://localhost:8080/api/pedidos -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"clienteId":1,"codigoPostalDestino":"43840","lineas":[
   {"producto":"Teclado","cantidad":2,"precioUnitario":45.99,"pesoUnitarioKg":0.8}]}'

# 3. Confirmacion: consulta al proveedor y anade el coste de envio al total
curl -s -X POST http://localhost:8080/api/pedidos/1/confirmacion -H "Authorization: Bearer $TOKEN"
```

**5. Parar y limpiar.**

```bash
docker compose down        # para los contenedores y conserva la base de datos
docker compose down -v     # borra también el volumen: la base se vuelve a inicializar
```

> El script `scripts/postgres-init.sh` (que aplica el DDL de `pedidos` y de `clientes`) solo se
> ejecuta cuando el volumen de PostgreSQL está vacío. Si cambias un `schema.sql`, deja el stack
> con `docker compose down -v` para que se vuelva a aplicar.

> La verificación completa del stack con Docker —los ocho servicios en marcha, el flujo completo
> por el gateway, el proveedor caído y el reintento— está en
> **[documentacion/EVIDENCIA.md](../documentacion/EVIDENCIA.md)** §4. El mismo stack, levantado en
> local sin Docker, también lo verifica `scripts/verificar-e2e.sh` (con H2, o con PostgreSQL real
> con `E2E_BD=postgres`).

## La consola web

Con los seis servicios en marcha, la consola está en **<http://localhost:8080/>**: el gateway la
publica (ruta `consola-web`) y todo su tráfico pasa por él, igual que el resto de la API.

**Cómo levantarla y entrar**

```bash
cp .env.example .env          # si aún no lo has hecho: rellena los secretos
docker compose up --build     # la primera vez compila las imágenes
```

Abre **<http://localhost:8080/>** e inicia sesión con:

| Campo | Valor |
|---|---|
| Usuario | `demo` (el de `AUTH_USUARIO`, por defecto) |
| Contraseña | la de `AUTH_USUARIO_PASSWORD` en tu `.env` |

Para verla sin abrir el fichero:

```bash
grep AUTH_USUARIO_PASSWORD .env        # -> AUTH_USUARIO_PASSWORD=demo-<lo que sea>
```

La contraseña **no está en el repositorio**: la genera el despliegue y se guarda en `.env`
(ignorado por git). El login no es un formulario con usuario y contraseña en el navegador, sino el
flujo OAuth2 `authorization_code` + **PKCE** contra el servidor de autorización (`:9000`): el
navegador pide un código, la consola lo canjea por el token con el *verifier* y **no guarda
ningún secreto** en el cliente. La lógica PKCE vive en `static/pkce.js` y está probada contra el
vector del RFC 7636 (`node --test`).

* Crear cliente, crear pedido y confirmar: los importes **no se envían**, los calcula el servidor.
* El token del proveedor tampoco está en el código: llega por `PROVEEDOR_LOGISTICO_TOKEN`.

> **El token del proveedor nunca está en el código fuente**: llega por la variable de entorno
> `PROVEEDOR_LOGISTICO_TOKEN`. Si falta, el servicio **no arranca** (fallo en el arranque, no un 500
> en la primera confirmación). Lo mismo para los secretos de OAuth2.

> **Alcance de la consola.** Es una demo de una sola página: no pagina los pedidos ni los exporta.
> Si creciera: un listado de pedidos con paginación por *keyset* y descarga en CSV, y un *frontend*
> con framework.

