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
./gradlew build                     # compila, 134 tests (unitarios + integracion) y cobertura JaCoCo
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

> El estado exacto de la verificación de `docker compose` está en
> **[documentacion/EVIDENCIA.md](documentacion/EVIDENCIA.md)** §4. El stack de servicios, en cambio,
> sí está verificado en ejecución por `scripts/verificar-e2e.sh`, que los levanta en local sin
> Docker (con H2, o con PostgreSQL real con `E2E_BD=postgres`).

### La consola web

Con los seis servicios en marcha, la consola está en **<http://localhost:8080/>**: el gateway la
publica (ruta `consola-web`) y todo su tráfico pasa por él, igual que el resto de la API.

* Inicia sesión con **`authorization_code` + PKCE** contra el servidor de autorización (`:9000`):
  el navegador pide un código, la consola lo canjea por el token con el *verifier* y **no guarda
  ningún secreto** en el cliente. La lógica PKCE vive en `static/pkce.js` y está probada contra el
  vector del RFC 7636 (`node --test`).
* Crear cliente, crear pedido y confirmar: los importes **no se envían**, los calcula el servidor.

> **El token del proveedor nunca está en el código fuente**: llega por la variable de entorno
> `PROVEEDOR_LOGISTICO_TOKEN`. Si falta, el servicio **no arranca** (fallo en el arranque, no un 500
> en la primera confirmación). Lo mismo para los secretos de OAuth2.

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
* **Secretos**: el token del proveedor llega de una credencial de Jenkins
  (`bdo-proveedor-token`), nunca del repositorio.
* **Gitea**: el *Config Server* ya admite el repositorio de configuración en Gitea
  (`CONFIG_PROFILE=git`); el resto del pipeline es agnóstico al servidor Git, así que el mismo
  `Jenkinsfile` vale para GitHub o Gitea cambiando el origen del *checkout*.

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
