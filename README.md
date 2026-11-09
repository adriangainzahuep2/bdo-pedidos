# BDO · Servicio de pedidos con integración logística

Prueba técnica **Desarrollador/a Java · Perfil Senior** (BDO España, 03/11/2026).

Servicio de pedidos en **Java 17 / Spring Boot 3.3 (WebFlux + R2DBC)** que crea pedidos con sus
líneas y, al confirmarlos, obtiene el **coste de envío** de un proveedor logístico externo lento
(hasta 5 s), con **503 intermitentes** y que exige un **token**. El foco está en lo que pide el
enunciado: resiliencia ante el proveedor, concurrencia y calidad de los tests.

> La arquitectura paso a paso, con diagramas de servicios, clases, entidades, estados y
> secuencias, y el catálogo de clases y funciones, está en
> **[docs/ARQUITECTURA.md](docs/ARQUITECTURA.md)**.
> Los patrones de diseño aplicados (dónde y para qué) están en
> **[docs/PATRONES.md](docs/PATRONES.md)**.
> El detalle razonado de cada decisión (qué, por qué, qué se descartó y cómo se verifica) está en
> **[docs/DECISIONES.md](docs/DECISIONES.md)**. La salida real de las verificaciones está en
> **[documentacion/EVIDENCIA.md](documentacion/EVIDENCIA.md)**.

---

## 1. Cómo arrancar la aplicación y los tests

### Requisitos

* **JDK 17** (el enunciado pide Java 17). El build compila con `--release 17`, así que la API
  usada está comprobada contra Java 17 aunque la JVM que ejecuta Gradle sea más moderna.
* No hace falta instalar Gradle: el repositorio incluye el *wrapper* (`./gradlew`, Gradle 8.10.2).
* Python 3 para el proveedor simulado y los scripts. Node.js 18+ para los tests de JavaScript de
  la consola web. PostgreSQL 13+ (binarios `initdb`/`psql`) solo para las pruebas sobre
  PostgreSQL. Docker solo para `docker compose`.

### Tests

```bash
./gradlew build                     # compila, 135 tests (unitarios + integracion) y cobertura JaCoCo
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

### Arrancar (local)

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

### Arrancar con Docker Compose

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
> **[documentacion/EVIDENCIA.md](documentacion/EVIDENCIA.md)** §4. El mismo stack, levantado en
> local sin Docker, también lo verifica `scripts/verificar-e2e.sh` (con H2, o con PostgreSQL real
> con `E2E_BD=postgres`).

### La consola web

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

### Cómo encaja la arquitectura

| Pieza | Puerto | Qué hace |
|---|---|---|
| **Gateway** (`pedidos-gateway`) | 8080 | Puerta única: valida el JWT, limita las peticiones (429), genera y propaga `X-Correlation-Id`, enruta cada ruta y publica la consola web |
| **Servidor de autorización** (`pedidos-auth`) | 9000 | OAuth2/JWT: `client_credentials` para los servicios y `authorization_code` + PKCE para la consola. Firma con una clave RSA que publica en `/oauth2/jwks` |
| **Registro** (`pedidos-registry`) | 8761 | Eureka: cada servicio se da de alta con su host y puerto y el resto lo descubre. Sin IP fija ni direcciones fijas en el código |
| **Config Server** (`pedidos-config`) | 8888 | Configuración compartida y **cifrada con JCE** (`{cipher}`). Con `CONFIG_PROFILE=git` la lee de un repositorio Gitea |
| **API de pedidos** (`pedidos-api`) | 8081 | Caso de uso: crear pedido, confirmar (consulta al proveedor, guarda el coste de envío y el total) y cancelar |
| **API de clientes** (`pedidos-cliente`) | 8082 | Alta y consulta de clientes, con su propia base de datos |
| **PostgreSQL** | 55432 | Una base por microservicio: `pedidos` y `clientes` |
| **Proveedor logístico simulado** | 8090 | Sustituye al proveedor real: puede tardar 5 s, devolver 503 intermitentes y exige un token |

**Eureka (descubrimiento de servicios).** No hay direcciones IP escritas en el código: cada
servicio se registra en Eureka (`@EnableDiscoveryClient`, perfil `eureka`) y los demás lo
consultan. El `instance-id` lleva un valor aleatorio (`pedidos-api:<uuid>`), así que pueden
convivir varias instancias del mismo servicio. El registro se refresca cada 5 s y la caché de
instancias del balanceador dura también 5 s: sin esa alineación, un servicio que se daba de alta
justo después de una consulta sería invisible durante 35 s y se respondería 503.

**Load balancing.** Las rutas del gateway apuntan a *service ids* (`lb://pedidos-api`,
`lb://pedidos-cliente`) y el servicio de pedidos llama al de clientes con un `WebClient`
anotado con `@LoadBalanced`: Spring Cloud LoadBalancer (el sustituto actual de Ribbon en el
ecosistema Netflix OSS) traduce ese *service id* a una instancia concreta del registro. Con
varias instancias detrás de la misma dirección, el reparto es de Round Robin. Se puede desactivar
con `app.clientes.load-balanced=false` (tests y entornos locales).

**Gateway.** Enruta solo las rutas declaradas (`/api/pedidos/**`, `/api/clientes/**` y la
consola); el *discovery locator* está **desactivado** a propósito, porque crearía una ruta
automática por cada servicio registrado y expondría servicios internos. Aplica un límite global
de peticiones que responde **429 sin encolar** (fallo rápido), propaga la correlación
(`X-Correlation-Id`, generado si no viene) y valida el JWT contra las claves del servidor de
autorización. Su *timeout* de respuesta (15 s) está por encima del presupuesto global de la
confirmación (8 s) para que sea el servicio de pedidos quien responde 504.

**RestTemplate.** El proveedor se consume **en modo reactivo** (`WebClient` sobre Netty), porque
el servicio pasa la mayor parte del tiempo esperando al proveedor y bloquear un hilo del *event
loop* sería un error de diseño. Existe además un **adaptador bloqueante con `RestTemplate`**
(`app.proveedor.modo=bloqueante`): delega la llamada en `Schedulers.boundedElastic()`, el pool
dedicado a E/S bloqueante, y comparte con el reactivo la clasificación de fallos, el circuito, el
límite, el *bulkhead* y la política de reintentos. Ambos adaptadores tienen **la misma batería de
tests**, lo que permite comparar las dos aproximaciones y sirve de camino natural para un equipo
con stack bloqueante (Spring MVC + JPA).

---

## 2. API

| Método | Ruta | Permiso | Respuesta |
|---|---|---|---|
| `POST` | `/api/pedidos` | `pedidos.write` | `201` + pedido `PENDIENTE` con importes calculados en el servidor |
| `POST` | `/api/pedidos/{id}/confirmacion` | `pedidos.write` | `200` + pedido `CONFIRMADO` con coste de envío, plazo y total |
| `POST` | `/api/pedidos/{id}/cancelacion` | `pedidos.write` | `200` + pedido `CANCELADO` |
| `GET` | `/api/pedidos/{id}` | `pedidos.read` | `200` |
| `POST`/`GET` | `/api/clientes`, `/api/clientes/{id}` | `clientes.write` / `clientes.read` | servicio de clientes |

La petición de creación **no tiene ningún campo de importe**: el servidor calcula importe de
línea, importe de productos, peso, bultos y total. Errores en formato RFC 9457 (`ProblemDetail`):

| Situación | HTTP |
|---|---:|
| Validación (sin líneas, cantidad 0, JSON mal formado...) | 400 |
| Sin token / sin permiso | 401 / 403 |
| Pedido inexistente | 404 |
| Ya hay una confirmación en curso · transición no permitida (p. ej. confirmar un cancelado) | 409 |
| Cliente inexistente | 422 |
| El proveedor falló (5xx, 4xx, respuesta vacía o inválida) | 502 |
| Proveedor no disponible (circuito abierto, límites, sin conexión) · servicio de clientes caído | 503 |
| El proveedor no respondió a tiempo | 504 |

---

## 3. Decisiones de diseño

### 3.1 Modelo y estados del pedido

Cliente (`id`, `nombre`, `email`), Pedido (`id`, cliente, estado, fechas, importe de productos,
coste de envío, importe total) y Línea (producto, cantidad, precio unitario). Dos añadidos
necesarios para el contrato del proveedor: **`codigoPostalDestino`** en el pedido y
**`pesoUnitarioKg`** en la línea (el servidor agrega `pesoKg`; `numBultos` = unidades, o el valor
declarado). El pedido guarda una **instantánea** del nombre y email del cliente.

```
PENDIENTE ──confirmar──▶ CONFIRMANDO ──ok──▶ CONFIRMADO (terminal)
    │  ▲                      │
    │  └──fallo del proveedor─┘   (y lease caducado: tarea de recuperacion)
    └──cancelar──▶ CANCELADO (terminal)
```

* `CONFIRMANDO` es el estado de "reserva" que garantiza la exclusión mutua (§3.3).
* Si el proveedor falla, el pedido **vuelve a `PENDIENTE`**: queda reintentable y el motivo se
  guarda en `ultimoError` y en la tabla de auditoría `pedido_confirmacion_intento`. Un estado de
  error terminal convertiría una caída del proveedor en un pedido bloqueado.
* Confirmar un pedido `CONFIRMADO` es **idempotente** (200, sin llamar otra vez al proveedor).
* La máquina de estados vive en el dominio (`EstadoPedido`) y la base de datos la refuerza con
  `CHECK` (un `CONFIRMADO` siempre tiene coste, total, plazo y fecha; ningún otro estado tiene total).

### 3.2 Resiliencia ante el proveedor

```
Bulkhead(32) → RateLimiter(50/s) → CircuitBreaker → timeout global 8 s → reintentos → timeout por intento 6 s → HTTP
```

| Decisión | Valor | Por qué |
|---|---|---|
| Timeout por intento | 6 s | El proveedor puede tardar 5 s legítimamente: 1 s de margen |
| Presupuesto global | 8 s | Lo único que espera el cliente, incluidos reintentos y esperas |
| Reintentos | 3 intentos, solo **503, 429 y errores de conexión** | Fallos rápidos y transitorios |
| **No** se reintenta | timeouts, 4xx, respuestas inválidas | Un timeout repetido multiplica la latencia sin garantía; un 4xx daría lo mismo |
| Espera | 200 ms × 2ⁿ ± 50 % *jitter*, o `Retry-After` | Si `Retry-After` no cabe en el presupuesto, se responde ya |
| Circuito | 50 % de fallos en 20 llamadas, 30 s abierto | Cuentan 5xx/429/timeouts/conexión/respuestas inválidas; **un 4xx no** |
| Estado del pedido tras cualquier fallo | `PENDIENTE` + intento auditado | Reintentable, nunca a medias |

El circuito envuelve a los reintentos: cuenta **una** petición de negocio, no cada intento. Los
dos adaptadores (WebClient por defecto, RestTemplate con `app.proveedor.modo=bloqueante`) comparten
la misma clasificación de fallos y la misma política, y se prueban con la misma batería.

### 3.3 Concurrencia: dos confirmaciones simultáneas

Ni `synchronized` (no vale entre instancias) ni `SELECT ... FOR UPDATE` (bloquearía la fila los
5 s del proveedor). Un **compare-and-set en base de datos**:

```sql
UPDATE pedido SET estado = 'CONFIRMANDO', fecha_inicio_confirmacion = ?, version = version + 1
 WHERE id = ? AND estado = 'PENDIENTE' AND version = ?
```

Solo una petición actualiza la fila; la otra recibe **409** y **no llama al proveedor**. El flujo
son tres fases cortas (tomar → llamar sin transacción abierta → persistir con otro UPDATE
condicional): un proveedor lento no retiene conexiones del pool. Una vez tomado el pedido, la
operación termina **aunque el cliente HTTP se desconecte**, y si el proceso muere a mitad, una
tarea programada devuelve a `PENDIENTE` los `CONFIRMANDO` con el *lease* (2 min) caducado.
Probado con 2 y con 10 peticiones HTTP simultáneas reales (una 200, el resto 409, una sola llamada
al proveedor), sobre PostgreSQL en el E2E, y la sentencia en `db/postgresql/verificacion.sql`.

### 3.4 Precisión decimal

`BigDecimal` en todo el dinero (nunca `double`), precios con 4 decimales e importes con 2,
**redondeo HALF_UP por línea** y el importe de productos es la suma de las líneas redondeadas
(el total cuadra con el detalle). Columnas `NUMERIC(19,2)`/`NUMERIC(19,4)`. La moneda viaja con el
importe y un proveedor que responde en otra moneda se rechaza (502).

### 3.5 Base de datos

H2 en memoria (modo PostgreSQL) para desarrollo y tests; **PostgreSQL** en despliegue (perfil
`postgres`), una base de datos por microservicio.

* `db/postgresql/esquema.sql`: esquema PostgreSQL completo (las 4 tablas con todos sus campos,
  restricciones e índices).
* `db/postgresql/datos-prueba.sql`: **10 filas por tabla**, cubriendo todos los estados y
  escenarios (503, timeout, circuito abierto, redondeos HALF_UP).
* `db/postgresql/verificacion.sql`: comprueba las invariantes de los datos y que las restricciones
  rechazan 15 tipos de datos incoherentes, además del compare-and-set, la recuperación y el borrado
  en cascada.
* `scripts/probar-postgresql.sh` lo ejecuta todo en un PostgreSQL desechable y comprueba que el
  esquema consolidado y el DDL de cada servicio son idénticos.

### 3.6 Arquitectura

```
cliente ─▶ API Gateway :8080 ─▶ pedidos-api :8081 ─▶ proveedor logístico (externo)
          (JWT, 429, correlación)      │
                                       └──▶ pedidos-cliente :8082
plataforma: pedidos-auth :9000 (OAuth2/JWT) · pedidos-registry :8761 (Eureka) · pedidos-config :8888
```

El enunciado **no pide microservicios**: es una decisión propia, con un coste explícito (más
piezas y más tiempo que las ~3 h orientativas). Un monolito modular sería la opción por defecto
para un solo equipo; el dominio (`pedidos-dominio`, Java puro con puertos) permite volver a él sin
tocar la lógica. Se mantiene porque las fronteras (pedidos, clientes y un proveedor externo con
modos de fallo propios) son reales y porque da pie a los escenarios de arquitectura de la
revisión. Es reactivo de extremo a extremo (WebFlux, R2DBC, WebClient) porque el servicio pasa la
mayor parte del tiempo **esperando** al proveedor.

---

## 4. Qué ha quedado fuera y cómo lo abordaría

| Fuera | Cómo se abordaría |
|---|---|
| **Los servicios no importan el Config Server** (existe y cifra con JCE, pero cada servicio usa su `application.yml` + entorno) | `spring-cloud-starter-config` + `spring.config.import=optional:configserver:` y el token como `{cipher}` |
| **Correlación en los logs** (la cabecera se propaga, pero no está en el MDC) | Micrometer Context Propagation (`Hooks.enableAutomaticContextPropagation`) o Micrometer Tracing con OTLP |
| **Idempotency-Key** en `POST /api/pedidos` (un reintento del cliente crea otro pedido) | Cabecera `Idempotency-Key` + tabla de claves con TTL |
| **Migraciones versionadas** (el DDL se aplica al crear la base de datos) | Flyway con `db/postgresql/esquema.sql` como `V1` |
| **Tests de integración Java sobre PostgreSQL** (el SQL se prueba con scripts y en el E2E) | Testcontainers en CI (requiere Docker en el agente) |
| **Listados y paginación** (el enunciado dice que no hacen falta) | `GET /api/pedidos` con paginación por *keyset* y cursor |
| **Gestión de la consola web** (hoy es una demo de una sola página, sin paginación de pedidos ni exportación) | Extraer los pedidos a un listado con *keyset* y descarga en CSV; mover la consola a un *frontend* con framework si crece |
| **Clave RSA persistente** del servidor de autorización y *rate limit* distribuido | KMS/Vault para la clave; `RequestRateLimiter` con Redis en el gateway |
| **Seguridad del Config Server** (`/encrypt` sin autenticación) | Spring Security en el Config Server o no exponerlo fuera de la red interna |
| **El repositorio de configuración en Gitea** (el Config Server lo soporta con `CONFIG_PROFILE=git`, pero hoy se sirve desde el classpath) | `spring.cloud.config.server.git.uri` apuntando al repo de Gitea, con el token como `{cipher}` |

---

## 5. CI/CD

`Jenkinsfile` (pipeline declarativo) con estas etapas:

| Etapa | Qué hace |
|---|---|
| Checkout | Recupera el commit y lo registra en el log |
| Build | `./gradlew clean build -x test` |
| Esquema PostgreSQL | `./scripts/probar-postgresql.sh` (PostgreSQL real, desechable) |
| Tests | `./gradlew test :pedidos-api:testJavaScript` (Java + `node --test` de la consola) |
| Cobertura | Informe JaCoCo (el que consume Sonar) |
| SonarQube | `./gradlew sonar` + *quality gate* (bloquea si la cobertura o las reglas no pasan) |
| Empaquetado | JARs de los ocho módulos |
| Imágenes Docker | Una por servicio, etiquetada con el número de build |
| Extremo a extremo | `./scripts/verificar-e2e.sh` sobre el stack completo |

* **Sonar**: configurado en `build.gradle.kts` (el plugin de Gradle **no** lee
  `sonar-project.properties`, solo el *scanner* de línea de comandos), con la cobertura de JaCoCo.
  El análisis es de **un solo proyecto** de Sonar: el proyecto raíz de Gradle no tiene código, así
  que se le indican explícitamente las fuentes, los tests y los informes de cobertura de cada
  módulo (`sonar.sources`, `sonar.tests`, `sonar.coverage.jacoco.xmlReportPaths`).
* **Secretos**: el token del proveedor llega de una credencial de Jenkins
  (`bdo-proveedor-token`), nunca del repositorio. El de SonarQube, de otra
  (`sonarqube-token`), y ambos se inyectan como variables de entorno.
* **Gitea**: el *Config Server* ya admite el repositorio de configuración en Gitea
  (`CONFIG_PERFIL=git`); el resto del pipeline es agnóstico al servidor Git, así que el mismo
  `Jenkinsfile` vale para GitHub o Gitea cambiando el origen del *checkout*.

### El stack de CI/CD en local (Jenkins + SonarQube + Gitea)

Hay un `compose` aparte, `docker-compose.ci.yml`, que levanta las tres piezas para poder ejecutar
el pipeline **de verdad** sin depender de una infraestructura externa:

```bash
./scripts/verificar-ci.sh      # levanta el stack, lo configura y lanza el pipeline
```

| Servicio | URL | Qué hace |
|---|---|---|
| Jenkins | <http://localhost:8091/> | Ejecuta el `Jenkinsfile` del repositorio (job `bdo-pedidos`) |
| SonarQube | <http://localhost:9001/> | Análisis y *quality gate* del proyecto `bdo-pedidos` |
| Gitea | <http://localhost:3000/> | Repositorio `bdo/config-repo` con la configuración que sirve el Config Server |

El script hace todo el Bootstrap:

1. Levanta SonarQube y Gitea y espera a que estén sanos.
2. Crea en Gitea el usuario `bdo` y el repositorio `config-repo`, y sube el contenido de
   `config-repo/` (la configuración compartida y la de cada servicio).
3. Crea en SonarQube el token de API y el **webhook** hacia Jenkins (`http://jenkins:8095/sonarqube-webhook/`).
4. Levanta Jenkins: su script de arranque (`docker/ci/jenkins/init.groovy.d/`) deja creados el
   job, las dos credenciales y el servidor de SonarQube.
5. Lanza el pipeline y sigue el log hasta el resultado.

**El agente de Jenkins es una imagen propia** (`docker/ci/jenkins/Dockerfile`) porque el pipeline
necesita, además del JDK 17 de la imagen oficial: Node 20 (los tests de JavaScript de la consola),
Python 3 (el proveedor simulado), los binarios de PostgreSQL (`initdb`/`pg_ctl`/`psql` para
`scripts/probar-postgresql.sh`) y el socket de Docker (para la etapa que construye las imágenes).

> Los puertos del stack de CI (8091, 9001, 3000) son distintos de los de la aplicación a
> propósito: el pipeline ejecuta `scripts/verificar-e2e.sh` **dentro** del contenedor de Jenkins, y
> ese script levanta los servicios de la aplicación en los puertos 8080, 9000, 8761, 8081 y 8082.
> Por eso Jenkins tampoco escucha en su 8080 habitual dentro del contenedor, sino en el 8095
> (`JENKINS_OPTS=--httpPort=8095`): con Jenkins en el 8080, el API Gateway del E2E no podía
> arrancar y la última etapa fallaba.

> **Nota de entorno**: Elasticsearch (el motor de SonarQube) bloquea sus índices en solo lectura
> cuando el disco del host supera el 95 % de uso (*flood-stage watermark*), y entonces el
> SonarQube no arranca. Por eso el compose arranca Elasticsearch con
> `-Des.disk.threshold_enabled=false`.

> La configuración del *Config Server* puede servirse desde Gitea en lugar del classpath:
> `CONFIG_PERFIL=git` y `CONFIG_GIT_URI` (con `CONFIG_GIT_USUARIO` y `CONFIG_GIT_PASSWORD`) en el
> `.env` hacen que el Config Server clone el repositorio `bdo/config-repo` de Gitea.

### Jenkins, SonarQube y Gitea: cómo se integran

| Pieza | Cómo entra en el flujo | Qué necesita |
|---|---|---|
| **Jenkins** | Lee el `Jenkinsfile` del repositorio (pipeline declarativo) y ejecuta las etapas en un agente | Agente con JDK 17+, Node.js 18+, Docker, Python 3 y binarios de PostgreSQL (`initdb`/`psql`) |
| **SonarQube** | La etapa `withSonarQubeEnv('SonarQube')` inyecta `SONAR_HOST_URL` y `SONAR_TOKEN` en el *build*; `./gradlew sonar` sube el código y la cobertura de JaCoCo | Un servidor SonarQube con el *webhook* apuntando a Jenkins (`<jenkins>/sonarqube-webhook/`), para que el *quality gate* bloquee la build |
| **Gitea** | El *Config Server* puede leer la configuración de un repositorio Gitea (`CONFIG_PROFILE=git` + `spring.cloud.config.server.git.uri`), con los secretos cifrados con JCE (`{cipher}`) | Un repositorio Gitea con el `config-repo/` y la clave `CONFIG_ENCRYPT_KEY` |

El `Jenkinsfile` no depende del servidor Git: `checkout scm` toma el origen de donde se haya
configurado el *job*, así que el mismo pipeline vale contra GitHub (como está ahora), GitLab o
Gitea. Los secretos viajan por la credencial de Jenkins `bdo-proveedor-token`, nunca por el
repositorio.

### Estado de la verificación de esta parte

Lo que **sí** está verificado ejecutándolo en la máquina de la entrega:

* Las etapas del pipeline son comandos del repositorio, y **todos** se han ejecutado a mano y en
  verde: `./gradlew clean build -x test`, `./scripts/probar-postgresql.sh`,
  `./gradlew test :pedidos-api:testJavaScript`, `./gradlew jacocoTestReport`, los seis
  `docker build --build-arg MODULO=...` y `./scripts/verificar-e2e.sh`.
* La configuración de Sonar vive en `build.gradle.kts` (el plugin de Gradle **no** lee
  `sonar-project.properties`, solo el *scanner* de línea de comandos) y la cobertura que consume es
  la de JaCoCo, con los informes en `build/reports/jacoco/test/jacoco.xml`.

Lo que **no** está verificado, y conviene decir con todas las letras: **el pipeline no se ha
ejecutado en Jenkins**, ni contra SonarQube, ni contra un Gitea real. No hay una instancia de
ninguno de los tres levantada en la máquina donde se preparó esta entrega, así que las etapas que
dependen de ellos (la que invoca `sonar` y la que espera al *quality gate*) **no se han podido
ejecutar**. Lo que hay es la *declaración* completa del pipeline y la comprobación manual de
cada comando por separado.

### Trabajo en equipo

El repositorio está en GitHub (`adriangainzahuep2/bdo-pedidos`) y el historial refleja el
trabajo por ramas y revisiones:

* Cada cambio va en su **rama** (`feature/...`, `docs/...`, `fix/...`), nunca directamente en
  `main`.
* Cada rama se sube y se abre un **pull request** con la descripción de qué cambia y **cómo se ha
  verificado**; ahí es donde se revisa.
* La fusión es un **merge commit** (no *squash*), para que se conserve el historial individual de
  commits y se vea qué tocó cada cambio.

Hay cuatro PR fusionados: la consola web con PKCE y el pipeline real, la reconstrucción del
historial de commits, las instrucciones de Docker y el *healthcheck* del servidor de autorización.

---

## 6. Uso de IA

He usado un asistente de IA (Claude) para generar código repetitivo, borradores de documentación
y, sobre todo, **para revisar**. Las decisiones de diseño (estados, compare-and-set, qué se
reintenta, qué abre el circuito) son mías y están razonadas en `docs/DECISIONES.md`.

Correcciones sobre lo que proponía la IA en la primera versión:

1. Reintentos con la firma antigua de `retryWhen`: reescrito con `Retry.from` de Reactor 3.6.
2. H2 no admite `INSERT ... RETURNING`: repositorios con SQL explícito y secuencias.
3. El servicio de clientes devolvía un `id` distinto de la fila insertada (consumía dos valores de
   la secuencia): se reserva el id antes del `INSERT`.
4. La llamada entre microservicios no propagaba el token (401): propagación por el Contexto de Reactor.
5. El claim `scope` llega como lista desde Spring Authorization Server: conversor estándar.

Una **revisión completa posterior** (también asistida por IA y verificada test a test) encontró y
corrigió defectos que los tests de entonces no cubrían:

* El circuito solo contaba excepciones de dominio, pero recibía las técnicas: **timeouts y errores
  de conexión nunca lo abrían** y un 400 sí. Ahora cada intento traduce su fallo y un predicado
  decide qué cuenta.
* Con el **circuito abierto** (0 llamadas) la auditoría rechazaba "0 intentos" y el pedido se quedaba
  en `CONFIRMANDO` respondiendo 422.
* Un **200 sin cuerpo** dejaba el pedido en `CONFIRMANDO`; un **JSON mal formado** acababa en 500.
* El timeout de respuesta de Netty llegaba como error de conexión: **se reintentaba un timeout**.
* Un **404 del servicio de clientes** (con cuerpo `ProblemDetail`) acababa en 503 en lugar de 422.
* La lectura de pedidos dejaba escapar el `Row` de R2DBC fuera de su *callback* (inseguro con el
  driver de PostgreSQL).
* La cancelación de la petición HTTP dejaba pedidos en `CONFIRMANDO` hasta el *lease*.
* El gateway no reenviaba el `X-Correlation-Id` que generaba y exponía rutas automáticas a todos los
  servicios registrados (incluido el de autorización).
* `docker compose` no podía arrancar (Eureka en otro puerto, Config Server sin perfil `native`,
  base de datos de clientes inexistente) y el Config Server tenía las propiedades mal anidadas.
* El **arranque real del stack** (verificado después, en `documentacion/EVIDENCIA.md` §4) destapó
  otro defecto: el servidor de autorización protegía su `/actuator/health` con el login, así que
  su contenedor nunca pasaba a `healthy`. Ahora la salud es pública, como en los demás servicios.
* El MDC se usaba como `ThreadLocal` en WebFlux; el plugin de Sonar no leía
  `sonar-project.properties`; el README afirmaba cosas que no eran ciertas (que el enunciado pedía
  Netflix OSS, un historial de Git que no existía).

Y en la adaptación a los requisitos de tecnología del enunciado:

* El proyecto se había llevado a Java 21; el enunciado pide **Java 17**. Se compila con
  `--release 17` (javac comprueba también que solo se usa la API de Java 17) y las imágenes usan
  JDK/JRE 17.
* La consola web llamaba a `/api/clientes` en el servicio de pedidos (404) y se usaba pegando un
  token. Ahora se publica por el gateway e inicia sesión con `authorization_code` + PKCE; la
  lógica PKCE está probada con `node --test` (vector del RFC 7636).
* El `Jenkinsfile` empezaba con comentarios `#`, que Groovy no admite: el pipeline no compilaba.
* El catálogo de versiones declaraba una versión (1.3.4) del starter del servidor de autorización
  que no correspondía a ningún artefacto; la gestiona el BOM de Spring Boot.

---

## 7. Estructura

```
pedidos-dominio/   modelo, máquina de estados, cálculo de importes, puertos (Java puro)
pedidos-api/       servicio de pedidos: API, aplicación, persistencia R2DBC, adaptadores del proveedor
                   y consola web (static/: HTML, CSS, JavaScript con PKCE)
pedidos-cliente/   servicio de clientes
pedidos-gateway/   API Gateway (Spring Cloud Gateway)
pedidos-auth/      servidor de autorización OAuth2
pedidos-registry/  Eureka · pedidos-config/ Config Server · pedidos-testkit/ JWT para tests
db/postgresql/     esquema PostgreSQL, datos de prueba y verificación
scripts/           proveedor simulado, prueba de PostgreSQL, E2E, init de PostgreSQL para compose
docs/ documentacion/  arquitectura, patrones, decisiones, evidencia, peticiones de ejemplo
Jenkinsfile · docker-compose.yml · Dockerfile · gradle/libs.versions.toml (versiones)
```
