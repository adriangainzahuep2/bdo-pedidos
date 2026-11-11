# BDO · Servicio de pedidos con integración logística

Prueba técnica **Desarrollador/a Java · Perfil Senior** (BDO España, 03/11/2026).

Servicio de pedidos en **Java 17 / Spring Boot 3.3 (WebFlux + R2DBC)** que crea pedidos con sus
líneas y, al confirmarlos, obtiene el **coste de envío** de un proveedor logístico externo lento
(hasta 5 s), con **503 intermitentes** y que exige un **token**. El foco está en lo que pide el
enunciado: resiliencia ante el proveedor, concurrencia y calidad de los tests.

| Documento | Qué contiene |
|---|---|
| [docs/ARRANQUE.md](docs/ARRANQUE.md) | Requisitos, tests, arranque en local y con Docker Compose, consola web |
| [docs/DECISIONES.md](docs/DECISIONES.md) | Cada decisión razonada: qué, por qué, qué se descartó y cómo se verifica |
| [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md) | Arquitectura paso a paso con diagramas (servicios, clases, estados, secuencias) |
| [docs/PLATAFORMA.md](docs/PLATAFORMA.md) | Circuit breakers, Eureka, eventos (RabbitMQ), trazas (Zipkin), métricas (Prometheus), Actuator; por qué no JPA ni OpenFeign |
| [docs/PATRONES.md](docs/PATRONES.md) | Patrones de diseño aplicados, dónde y para qué |
| [docs/CICD.md](docs/CICD.md) | Pipeline de Jenkins, SonarQube y Gitea, y cómo ejecutarlo en local |
| [documentacion/EVIDENCIA.md](documentacion/EVIDENCIA.md) | Salida real de cada verificación: tests, PostgreSQL, Docker, CI/CD, plataforma |

---

## 1. Arrancar

Requisitos: **JDK 17** (se compila con `--release 17`), Python 3, Node.js 18+ para los tests de la
consola y Docker para el stack completo. Gradle va incluido (`./gradlew`).

```bash
./gradlew build                     # compila, 159 tests (unitarios + integración) y cobertura JaCoCo
./scripts/verificar-e2e.sh          # stack completo y escenarios del enunciado a través del gateway

cp .env.example .env                # secretos (no están en el repositorio): rellenarlos
docker compose up --build           # seis servicios, PostgreSQL, RabbitMQ, Zipkin, Prometheus y proveedor simulado
```

La consola web queda en <http://localhost:8080/> (usuario `demo`, contraseña la de
`AUTH_USUARIO_PASSWORD` en `.env`). El arranque en local sin Docker, la tabla de secretos, los
puertos y los ejemplos con `curl` están en [docs/ARRANQUE.md](docs/ARRANQUE.md);
`documentacion/pedidos.http` tiene todas las peticiones listas para el IDE.

Los dos tests que pide el enunciado:

* **Cálculo del total**: `CalculadoraTotalPedidoTest` (dominio puro, sin Spring).
* **Error o timeout del proveedor** (WireMock): `ProveedorTarifasClientReactivoTest` (503 reintentado,
  timeout no reintentado, respuestas inválidas, circuito) y, en la API,
  `PedidoControllerTest.falloDelProveedor` (502 y el pedido vuelve a `PENDIENTE`) y
  `PedidoControllerTimeoutTest` (504).

---

## 2. API

| Método | Ruta | Permiso | Respuesta |
|---|---|---|---|
| `POST` | `/api/pedidos` | `pedidos.write` | `201` + pedido `PENDIENTE` con importes calculados en el servidor |
| `POST` | `/api/pedidos/{id}/confirmacion` | `pedidos.write` | `200` + pedido `CONFIRMADO` con coste de envío, plazo y total |
| `POST` | `/api/pedidos/{id}/cancelacion` | `pedidos.write` | `200` + pedido `CANCELADO` |
| `GET` | `/api/pedidos/{id}` | `pedidos.read` | `200` |
| `POST`/`GET` | `/api/clientes`, `/api/clientes/{id}` | `clientes.write` / `clientes.read` | servicio de clientes |
| `GET` | `/api/clientes/{id}/pedidos-confirmados` | `clientes.read` | `200` + resumen alimentado por eventos |

La petición de creación **no tiene ningún campo de importe**: el servidor calcula importe de
línea, importe de productos, peso, bultos y total. Errores en formato RFC 9457 (`ProblemDetail`):

| Situación | HTTP |
|---|---:|
| Validación (sin líneas, cantidad 0, JSON mal formado...) | 400 |
| Sin token / sin permiso | 401 / 403 |
| Pedido inexistente | 404 |
| Confirmación en curso · transición no permitida (p. ej. confirmar un cancelado) | 409 |
| Cliente inexistente | 422 |
| El proveedor falló (5xx, 4xx, respuesta vacía o inválida) | 502 |
| Proveedor no disponible (circuito abierto, límites) · microservicio caído | 503 |
| El proveedor (o un microservicio) no respondió a tiempo | 504 |

---

## 3. Decisiones clave

Resumen; el razonamiento completo, con lo descartado y cómo se verifica, está en
[docs/DECISIONES.md](docs/DECISIONES.md).

**Estados.** `PENDIENTE → CONFIRMANDO → CONFIRMADO`, y `CANCELADO`. Si el proveedor falla, el
pedido **vuelve a `PENDIENTE`** (reintentable) con el motivo en `ultimoError` y una fila de
auditoría por intento; nunca queda a medias. Confirmar un pedido confirmado es idempotente (200,
sin llamar al proveedor). La base de datos refuerza la máquina de estados con `CHECK`.

**Resiliencia ante el proveedor.**

```
Bulkhead(32) → RateLimiter(50/s) → CircuitBreaker → timeout global 8 s → reintentos → timeout por intento 6 s → HTTP
```

6 s por intento (el proveedor puede tardar 5 s legítimamente); 8 s de presupuesto total; 3
intentos con *backoff* exponencial y *jitter* **solo** ante 503, 429 y errores de conexión (un
timeout no se reintenta: multiplicaría la latencia); `Retry-After` si cabe en el presupuesto. El
circuito envuelve a los reintentos y un 4xx no lo abre. El token llega por
`PROVEEDOR_LOGISTICO_TOKEN`: sin él, el servicio no arranca.

**Concurrencia.** Ni `synchronized` (no vale entre instancias) ni `SELECT ... FOR UPDATE`
(bloquearía la fila los 5 s del proveedor): un **compare-and-set** en base de datos,

```sql
UPDATE pedido SET estado = 'CONFIRMANDO', version = version + 1 WHERE id = ? AND estado = 'PENDIENTE' AND version = ?
```

Solo una confirmación toma el pedido; la otra recibe 409 sin llamar al proveedor. La llamada se
hace sin transacción abierta, termina aunque el cliente se desconecte, y un *lease* de 2 min
recupera los pedidos de un proceso que murió a mitad. Probado con 10 peticiones HTTP simultáneas.

**Precisión decimal.** `BigDecimal` en todo el dinero, precios con 4 decimales e importes con 2,
**HALF_UP por línea** y el total es la suma de las líneas redondeadas; `NUMERIC` en la base de
datos. Un proveedor que responde en otra moneda se rechaza (502).

**Arquitectura.** Seis servicios (gateway, auth OAuth2/JWT, Eureka, Config Server, pedidos,
clientes) y reactivo de extremo a extremo, porque el servicio pasa casi todo el tiempo
**esperando** al proveedor. El enunciado **no pide microservicios**: es una decisión propia con un
coste explícito; el dominio (`pedidos-dominio`, Java puro con puertos) permite volver a un monolito
modular sin tocar la lógica. La plataforma —circuit breaker por microservicio en el gateway, evento
asíncrono `PedidoConfirmado` por RabbitMQ, trazas en Zipkin, métricas en Prometheus— y por qué no
se usan JPA ni OpenFeign están en [docs/PLATAFORMA.md](docs/PLATAFORMA.md).

**Base de datos.** H2 (modo PostgreSQL) en desarrollo y tests; **PostgreSQL** en despliegue, una
base por servicio. `db/postgresql/` tiene el esquema completo, 10 filas de prueba por tabla y una
verificación que comprueba las invariantes y que las restricciones rechazan 18 tipos de datos
incoherentes (`./scripts/probar-postgresql.sh`).

---

## 4. Qué ha quedado fuera y cómo lo abordaría

| Fuera | Cómo se abordaría |
|---|---|
| **Los servicios no importan el Config Server** (sirve la configuración desde un directorio o desde Gitea y cifra con JCE, pero cada servicio usa su `application.yml` + entorno) | `spring.config.import=optional:configserver:` y el token como `{cipher}` |
| **Publicación de eventos con garantía** (hoy es de mejor esfuerzo: con RabbitMQ caído, el pedido se confirma y el evento se pierde, contado en una métrica) | Patrón outbox ([docs/PLATAFORMA.md](docs/PLATAFORMA.md) §4.6) |
| **Idempotency-Key** en `POST /api/pedidos` (un reintento del cliente crea otro pedido) | Cabecera `Idempotency-Key` + tabla de claves con TTL |
| **Tests de integración Java sobre PostgreSQL** (el SQL se prueba con scripts y en el E2E) | Testcontainers (el agente de Jenkins ya tiene Docker) |
| **Migraciones versionadas** (el DDL se aplica al crear la base de datos) | Flyway con `db/postgresql/esquema.sql` como `V1` |
| **Paneles y alertas** (las métricas están en Prometheus) | Grafana y Alertmanager: p99 de la confirmación, circuitos abiertos, eventos fallidos |
| **Clave RSA persistente** del servidor de autorización y *rate limit* distribuido | KMS/Vault; `RequestRateLimiter` con Redis en el gateway |
| **Seguridad del Config Server** (`/encrypt` sin autenticación) | Spring Security en el Config Server o no exponerlo fuera de la red interna |
| **Listados y paginación** (el enunciado dice que no hacen falta) | `GET /api/pedidos` con paginación por *keyset* |

---

## 5. CI/CD

`Jenkinsfile` declarativo de diez etapas: build → esquema PostgreSQL → tests (Java y JavaScript) →
cobertura → SonarQube con *quality gate* bloqueante → empaquetado → seis imágenes Docker
etiquetadas con el número de build → E2E. `docker-compose.ci.yml` y `scripts/verificar-ci.sh`
levantan Jenkins, SonarQube y Gitea en local y ejecutan el pipeline de verdad. Detalle en
[docs/CICD.md](docs/CICD.md); salida real en [EVIDENCIA.md](documentacion/EVIDENCIA.md) §4 bis.

Cada cambio va en su rama y entra por *pull request* con *merge commit* (no *squash*), con la
descripción de qué cambia y cómo se ha verificado.

---

## 6. Uso de IA

He usado un asistente de IA (Claude) para código repetitivo, borradores de documentación y, sobre
todo, **para revisar**. Las decisiones de diseño (estados, compare-and-set, qué se reintenta, qué
abre el circuito) son mías y están razonadas en [docs/DECISIONES.md](docs/DECISIONES.md).

Varias propuestas compilaban y pasaban los tests de entonces, pero estaban mal: el circuito no se
abría con timeouts y sí con un 400; un timeout de Netty se reintentaba como error de conexión;
con el circuito abierto el pedido se quedaba en `CONFIRMANDO`; la llamada al proveedor empezaba
una traza nueva. Se encontraron con tests que reproducen el fallo y ejecutando el stack real. La
lista completa está en [docs/DECISIONES.md](docs/DECISIONES.md) §13.

---

## 7. Estructura

```
pedidos-dominio/   modelo, máquina de estados, cálculo de importes, evento PedidoConfirmado, puertos (Java puro)
pedidos-api/       servicio de pedidos: API, aplicación, persistencia R2DBC, proveedor, eventos y consola web
pedidos-cliente/   servicio de clientes y consumidor de PedidoConfirmado
pedidos-gateway/   API Gateway (Spring Cloud Gateway) con circuit breaker por microservicio
pedidos-auth/      servidor de autorización OAuth2
pedidos-registry/  Eureka · pedidos-config/ Config Server · pedidos-testkit/ JWT para tests
db/postgresql/     esquema PostgreSQL, datos de prueba y verificación
scripts/           proveedor simulado, prueba de PostgreSQL, E2E, CI local, init de PostgreSQL
docs/ documentacion/  arquitectura, decisiones, plataforma, patrones, CI/CD, arranque, evidencia
Jenkinsfile · docker-compose.yml · docker-compose.ci.yml · Dockerfile · gradle/libs.versions.toml
```
