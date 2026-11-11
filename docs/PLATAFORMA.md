# Plataforma: resiliencia, comunicación, observabilidad y persistencia

Qué hace cada pieza de infraestructura del sistema, **cómo está implementada** (ficheros y
configuración reales), cómo se comprueba y qué límites tiene. Las decisiones de diseño razonadas
están en [DECISIONES.md](DECISIONES.md); la salida real de las verificaciones, en
[EVIDENCIA.md](../documentacion/EVIDENCIA.md) §6.

## Índice

1. [Mapa: qué hay y qué no](#1-mapa-qué-hay-y-qué-no)
2. [Tolerancia a fallos y circuit breaking](#2-tolerancia-a-fallos-y-circuit-breaking)
3. [Registro de servicios y comunicación entre servicios](#3-registro-de-servicios-y-comunicación-entre-servicios)
4. [Comunicación asíncrona: Spring Cloud Stream y RabbitMQ](#4-comunicación-asíncrona-spring-cloud-stream-y-rabbitmq)
5. [Observabilidad: logs, métricas y trazas](#5-observabilidad-logs-métricas-y-trazas)
6. [Spring Boot Actuator](#6-spring-boot-actuator)
7. [Persistencia: R2DBC y por qué no JPA](#7-persistencia-r2dbc-y-por-qué-no-jpa)
8. [Clientes HTTP: WebClient y por qué no OpenFeign](#8-clientes-http-webclient-y-por-qué-no-openfeign)
9. [Verlo funcionando](#9-verlo-funcionando)

---

## 1. Mapa: qué hay y qué no

| Capacidad | Estado | Dónde |
|---|---|---|
| Circuit breaker hacia el **proveedor externo** | Implementado | `pedidos-api` (Resilience4j: circuito, reintentos, bulkhead, limitador, timeouts) |
| Circuit breaker hacia el **servicio de clientes** | Implementado | `pedidos-api` (`servicioClientes`) |
| Circuit breaker en el **API Gateway**, por microservicio | Implementado | `pedidos-gateway` (filtro `CircuitBreaker` + fallback `ProblemDetail`) |
| Limitador de peticiones en el gateway | Implementado | `pedidos-gateway` (`FiltroLimitePeticiones`, 429) |
| Reintentos y cola de mensajes muertos del **consumidor** | Implementado | `pedidos-cliente` (binder de RabbitMQ) |
| Registro y descubrimiento (Eureka) + balanceo | Implementado | `pedidos-registry`, Spring Cloud LoadBalancer |
| Comunicación síncrona entre servicios | Implementado | `WebClient` `@LoadBalanced`, con token y traza propagados |
| Comunicación **asíncrona** (eventos) | Implementado | Spring Cloud Stream + RabbitMQ: `PedidoConfirmado` |
| **Trazas distribuidas** (Zipkin) | Implementado | Micrometer Tracing + Brave en gateway, auth, pedidos y clientes |
| **Logs** con traza y correlación | Implementado | `[servicio,traceId,spanId,X-Correlation-Id]` en cada línea |
| **Métricas** (Prometheus) | Implementado | `/actuator/prometheus` + `prometheus` en el compose |
| Spring Boot **Actuator** | Implementado | Los seis servicios |
| **JPA / Jakarta Persistence** | **Descartado** | Bloqueante: incompatible con WebFlux. Se usa R2DBC (§7) |
| **OpenFeign** | **Descartado** | Bloqueante. Se usa `WebClient` (§8) |
| Garantía "al menos una vez" en la **publicación** | Pendiente | Patrón outbox (§4.6) |
| Panel de Grafana y alertas | Pendiente | Las métricas ya están en Prometheus |

Los servicios de plataforma (`pedidos-auth`, `pedidos-registry`, `pedidos-config`) no llaman a
otros servicios: no tienen dependencias salientes que proteger con un circuito. Lo que los
protege es lo de alrededor: el arranque encadenado por *healthchecks* en el compose y el
circuito del gateway delante de las APIs.

---

## 2. Tolerancia a fallos y circuit breaking

### 2.1 El patrón

Un **circuit breaker** (cortocircuito) cuenta los resultados de las llamadas a una dependencia.
Si la proporción de fallos supera un umbral, se **abre**: durante un tiempo no se llama a la
dependencia y se falla en el acto. Después pasa a **semiabierto**, deja pasar unas pocas
llamadas de prueba y, según el resultado, se cierra o vuelve a abrirse. Así una dependencia
caída no arrastra al que la llama: no se acumulan peticiones esperando timeouts, y la
dependencia tiene tiempo de recuperarse sin recibir tráfico.

La implementación es **Resilience4j** en todo el sistema (es la biblioteca que recomienda Spring
Cloud desde que Hystrix se abandonó).

### 2.2 Capa 1: el API Gateway, un circuito por microservicio

**Qué resuelve.** Si `pedidos-api` se cae (o Eureka no tiene instancias), cada petición al
gateway esperaría al timeout de conexión y acabaría en un error de red sin formato. Con el
circuito, el gateway responde un `503` claro al momento y no satura un servicio que intenta
arrancar.

**Implementación.**

* Dependencia `spring-cloud-starter-circuitbreaker-reactor-resilience4j`
  (`pedidos-gateway/build.gradle.kts`).
* Cada ruta lleva el filtro `CircuitBreaker` (`pedidos-gateway/src/main/resources/application.yml`):

  | Ruta | Circuito | Fallback |
  |---|---|---|
  | `/api/pedidos/**` | `pedidosApi` | `forward:/fallback/pedidos-api` |
  | `/api/clientes/**` | `clientesApi` | `forward:/fallback/pedidos-cliente` |
  | Consola web (`/`, `/app.js`...) | `pedidosApi` (la sirve `pedidos-api`) | `forward:/fallback/pedidos-api` |

  Un circuito **por microservicio de destino**: si `pedidos-api` cae, las rutas de clientes
  siguen abiertas.
* Configuración común (`resilience4j.circuitbreaker.configs.microservicio`): ventana de 20
  llamadas, mínimo 10, se abre con el 50 % de fallos, 15 s abierto, 3 llamadas de prueba.
* `FallbackController` (`pedidos-gateway/.../fallback/`) responde en `ProblemDetail`, igual que
  los microservicios:

  | Causa | Respuesta |
  |---|---|
  | Circuito abierto, conexión rechazada, sin instancias | `503` `microservicio-no-disponible` + `Retry-After: 15` |
  | El microservicio no responde a tiempo (`response-timeout`, 15 s) | `504` `microservicio-sin-respuesta` |

**Dos decisiones que no son las de por defecto:**

1. **Qué cuenta como fallo.** Solo las **excepciones** hacia el microservicio (no conecta, no
   responde). Sus respuestas `5xx` **no**: cuando el proveedor logístico falla, `pedidos-api`
   responde `502`/`503`/`504`, y esa es su forma correcta de informar. Si el gateway las contase,
   una caída del proveedor abriría el circuito de toda la API de pedidos (también las consultas,
   que no dependen del proveedor).
2. **El TimeLimiter.** Spring Cloud CircuitBreaker envuelve cada llamada en un TimeLimiter que
   corta a **1 s** por defecto. Una confirmación tarda legítimamente hasta 8 s (el presupuesto
   del proveedor), así que se fija en 16 s, por encima del `response-timeout` del gateway (15 s):
   el límite efectivo es el del cliente HTTP.

**Cómo se comprueba.** `GatewayCircuitBreakerTest` (7 tests): microservicio caído → 503, también en escrituras (POST); tras 4
fallos el circuito se abre **solo para su ruta**; abierto → la petición no llega; lento → 504;
una respuesta de 1,5 s sí llega (el test falla si el TimeLimiter vuelve a 1 s, comprobado con
una prueba de mutación); un `503` del microservicio pasa tal cual y no cuenta como fallo.

### 2.3 Capa 2: `pedidos-api` hacia el proveedor logístico

Es la capa que pide el enunciado y la más elaborada. Resumen (detalle en
[DECISIONES.md](DECISIONES.md) §5):

```
Bulkhead(32) → RateLimiter(50/s) → CircuitBreaker → timeout global 8 s → reintentos → timeout por intento 6 s → HTTP
```

* **Circuito `proveedorLogistico`**: el predicado `ClasificadorFallosProveedor.CuentaParaElCircuito`
  decide qué es fallo (5xx, 429, timeouts, conexión, respuestas inválidas; un 4xx no).
* El circuito **envuelve a los reintentos**: una petición de negocio cuenta como un fallo, no tres.
* **Bulkhead** (32 concurrentes) y **limitador** (50/s) rechazan sin encolar.

### 2.4 Capa 3: `pedidos-api` hacia el servicio de clientes

Circuito `servicioClientes` (`AplicacionConfig`) y timeout de 2 s en `ClienteServiceClient`. Si
el servicio de clientes cae, la creación de pedidos responde `503` sin esperar y sin saturarlo.
Leer o confirmar un pedido no depende de clientes (el pedido guarda una instantánea del cliente).

### 2.5 Capa 4: el consumidor de eventos

El consumo asíncrono tiene su propia tolerancia a fallos (§4.4): 3 intentos con espera
exponencial y, agotados, la **cola de mensajes muertos**. Un evento que no se puede procesar no
bloquea los siguientes ni se pierde.

### 2.6 Métricas de los circuitos

Con `resilience4j-micrometer`, cada circuito, limitador y bulkhead publica sus métricas en
Prometheus: `resilience4j_circuitbreaker_state{name="pedidosApi",state="open"}`,
`resilience4j_circuitbreaker_failure_rate`, `resilience4j_ratelimiter_available_permissions`,
`resilience4j_bulkhead_available_concurrent_calls`... Los reintentos del adaptador reactivo son
un operador de Reactor (`Retry.from`), no un `Retry` de Resilience4j, así que no generan métricas
`resilience4j_retry_*`: cada intento queda en el log y en la tabla de auditoría
`pedido_confirmacion_intento`. Un circuito abierto es la primera alerta que se configuraría.

---

## 3. Registro de servicios y comunicación entre servicios

### 3.1 Eureka: el registro

**Qué resuelve.** Ningún servicio conoce la IP ni el puerto de otro. Cada instancia se registra
al arrancar y los demás la descubren por su nombre lógico, así que se pueden añadir instancias,
moverlas o reiniciarlas sin tocar configuración.

**Implementación.**

* `pedidos-registry` es un **Eureka Server** (`@EnableEurekaServer`, puerto 8761, consola web).
* Gateway, `pedidos-api` y `pedidos-cliente` son clientes (perfil `eureka`):
  `eureka.client.service-url.defaultZone`, `prefer-ip-address: true` e
  `instance-id: ${spring.application.name}:${random.value}` (varias instancias del mismo servicio
  conviven con identificadores distintos).
* El registro se refresca cada **5 s** y la caché del balanceador dura también **5 s**: con los
  35 s por defecto, una instancia recién registrada era invisible durante ese tiempo y se
  respondía 503.

### 3.2 Spring Cloud LoadBalancer: el balanceo en el cliente

**Qué resuelve.** Con varias instancias de un servicio, cada llamada elige una (Round Robin). El
balanceo lo hace quien llama, sin un balanceador central.

**Implementación.**

* Gateway: las rutas apuntan a `lb://pedidos-api` y `lb://pedidos-cliente`.
* `pedidos-api` → `pedidos-cliente`: un `WebClient.Builder` anotado con `@LoadBalanced`
  (`ClienteServiceConfig`); el `BeanPostProcessor` de LoadBalancer le añade el filtro que traduce
  `http://pedidos-cliente` a una instancia concreta. `app.clientes.load-balanced=false` lo
  desactiva (tests y E2E local).

### 3.3 Qué viaja en cada llamada entre servicios

| Cabecera | Para qué | Quién la pone |
|---|---|---|
| `Authorization: Bearer <JWT>` | El servicio de clientes valida el mismo token | `FiltroPropagacionToken` (Contexto de Reactor) |
| `traceparent` (W3C) | La llamada es un span hijo de la petición | Micrometer Tracing (el `WebClient` lleva el `ObservationRegistry`) |
| `X-Correlation-Id` | Identificador de negocio de la petición | *Baggage* de Micrometer Tracing |

### 3.4 Síncrono o asíncrono

| Comunicación | Tipo | Por qué |
|---|---|---|
| Crear pedido → validar cliente | **Síncrona** (HTTP) | La respuesta depende de ella: sin cliente, `422` |
| Confirmar pedido → proveedor | **Síncrona** (HTTP) | El cliente espera el coste de envío en la respuesta |
| Pedido confirmado → servicio de clientes | **Asíncrona** (evento) | Nadie espera la respuesta; el servicio de clientes puede estar caído sin afectar a la confirmación |

---

## 4. Comunicación asíncrona: Spring Cloud Stream y RabbitMQ

### 4.1 Qué se comunica y por qué así

Cuando un pedido queda `CONFIRMADO`, `pedidos-api` publica el **evento de dominio
`PedidoConfirmado`**. `pedidos-cliente` lo consume y mantiene una **proyección local**: los pedidos
confirmados de cada cliente y su importe, que expone en `GET /api/clientes/{id}/pedidos-confirmados`.

Ventajas frente a que el servicio de clientes pregunte al de pedidos:

* **Desacoplamiento temporal**: si `pedidos-cliente` está parado, la confirmación responde igual;
  los eventos esperan en su cola y se procesan al volver.
* **Sin dependencia en tiempo de lectura**: el resumen se responde con datos locales.
* **Extensible**: otro servicio (facturación, notificaciones) se suscribiría al mismo destino con
  su propio grupo, sin tocar `pedidos-api`.

El precio es la **consistencia en diferido**: el resumen refleja la confirmación unos
milisegundos después (o más tarde si el broker estuvo caído).

### 4.2 Spring Cloud Stream

**Qué es.** Una abstracción sobre los brokers de mensajes: el código publica y consume en
*bindings* con nombre, y un **binder** (RabbitMQ, Kafka...) los conecta al broker real. Cambiar
de broker es cambiar el binder y la configuración, no el código.

**Implementación del productor** (`pedidos-api`):

* Evento de dominio `PedidoConfirmado` (`pedidos-dominio/.../evento/`): *record* inmutable, solo
  se puede construir de un pedido `CONFIRMADO`; importes en `BigDecimal` con su moneda.
* Puerto `EventosPedidoPort` (dominio) y adaptador `PublicadorEventosStream` (`.../api/eventos/`):
  `StreamBridge.send("pedidoConfirmado-out-0", mensaje)` en `boundedElastic` (el envío del binder
  es bloqueante). Cabeceras `tipoEvento` y `pedidoId` para enrutar sin leer el cuerpo.
* `PedidoService` publica **después de persistir la confirmación y fuera de la transacción**, y
  después del `onErrorResume` que revierte: un fallo al publicar nunca devuelve a `PENDIENTE` un
  pedido confirmado. Una confirmación idempotente (ya estaba confirmado) no vuelve a publicar.
* Binding: `pedidoConfirmado-out-0 → destination: pedidos.confirmados` (un *exchange* `topic`).

**Implementación del consumidor** (`pedidos-cliente`):

* `ConsumidorEventosConfig.ConsumidorPedidoConfirmado implements Consumer<PedidoConfirmadoEvento>`:
  modelo funcional de Spring Cloud Stream; el bean `pedidoConfirmado` se enlaza con
  `pedidoConfirmado-in-0`.
* Binding: destino `pedidos.confirmados`, **grupo `pedidos-cliente`**. El grupo crea una cola
  duradera propia (`pedidos.confirmados.pedidos-cliente`): los mensajes no se pierden si el
  servicio está parado, y con varias instancias cada mensaje lo procesa solo una.
* `PedidoConfirmadoEvento` es una **copia propia del contrato** (no depende del módulo de pedidos)
  con `@JsonIgnoreProperties(ignoreUnknown = true)`: el consumidor lee solo lo que usa y tolera
  campos nuevos (*tolerant reader*).

### 4.3 Activación: el perfil `eventos`

Los eventos solo se activan con el perfil `eventos` (lo tiene el `docker-compose.yml`, donde está
RabbitMQ). Sin él, `pedidos-api` usa un adaptador que no hace nada y `pedidos-cliente` no crea el
consumidor: el E2E local y el pipeline de Jenkins, que no tienen broker, funcionan igual.

El broker **no** forma parte de `/actuator/health` (`management.health.rabbit.enabled: false`):
si RabbitMQ cae, los pedidos se siguen confirmando, y marcar el servicio como `DOWN` haría que un
orquestador lo reiniciase sin motivo.

### 4.4 Tolerancia a fallos del consumo

| Situación | Qué pasa |
|---|---|
| Error al procesar (base de datos caída, cliente desconocido) | 3 intentos con espera exponencial (0,5 s, 1 s) |
| Agotados los intentos | El mensaje va a la **cola de mensajes muertos** `pedidos.confirmados.pedidos-cliente.dlq` (`auto-bind-dlq`, `republish-to-dlq`); los siguientes siguen procesándose |
| Mensaje repetido (la entrega es "al menos una vez") | **Consumidor idempotente**: la clave primaria de la proyección es el pedido; la violación de esa clave se interpreta como "ya registrado" y se cuenta en `clientes.eventos.recibidos{resultado="duplicado"}` |

La idempotencia no usa `ON CONFLICT DO NOTHING` (SQL de PostgreSQL que H2 no admite): un `INSERT`
normal y `DuplicateKeyException` → "repetido". Otros errores de integridad (una clave foránea)
se propagan para que se reintenten y acaben en la DLQ.

### 4.5 Persistencia de la proyección

Tabla `cliente_pedido_confirmado` en la base de datos de clientes (y en el esquema consolidado
`db/postgresql/esquema.sql`): clave primaria `pedido_id`, clave foránea al cliente e importe
positivo. `db/postgresql/datos-prueba.sql` tiene 10 filas que cuadran con los pedidos confirmados
de la muestra, y `verificacion.sql` comprueba esa correspondencia y que se rechazan un evento
repetido, un cliente inexistente y un importe cero.

### 4.6 Límite conocido: la publicación es de mejor esfuerzo

Si RabbitMQ está caído en el momento de confirmar, el pedido queda confirmado en la base de datos
pero el evento **no se publica** (se registra en el log y en
`pedidos.eventos.publicados{resultado="error"}`). Para garantizar "al menos una vez" en la
publicación, el siguiente paso es el **patrón outbox**: insertar el evento en una tabla `outbox`
en la misma transacción que la confirmación, y que un proceso aparte lo publique y lo marque. Los
consumidores ya son idempotentes, que es lo que el outbox exige.

### 4.7 Cómo se comprueba

* `PedidoConfirmadoTest` (dominio): el evento lleva los importes exactos; no se puede crear de un
  pedido sin confirmar.
* `PedidoServiceTest`: se publica un único evento al confirmar; ninguno si el proveedor falla o si
  la confirmación es idempotente.
* `PublicadorEventosStreamTest`: con el broker caído completa sin error y cuenta el fallo.
* `PublicacionEventosIntegrationTest` y `ConsumoEventosIntegrationTest`: el binder de test de
  Spring Cloud Stream (en memoria) en lugar de RabbitMQ; mensaje real de extremo a extremo,
  idempotencia, campos desconocidos, cliente desconocido.
* Contra RabbitMQ real: [EVIDENCIA.md](../documentacion/EVIDENCIA.md) §6.

---

## 5. Observabilidad: logs, métricas y trazas

Los tres pilares responden a preguntas distintas: las **métricas**, *¿va algo mal?* (tendencias y
alertas); las **trazas**, *¿dónde?* (qué servicio y qué llamada de una petición concreta); los
**logs**, *¿por qué?* (el detalle). El `traceId` los une: aparece en la traza de Zipkin y en cada
línea de log de esa petición.

### 5.1 Trazas distribuidas: Micrometer Tracing, Brave y Zipkin

**Qué es.** Una **traza** es el recorrido de una petición por todo el sistema; se compone de
**spans** (una operación con inicio y duración: la petición al gateway, la llamada a clientes,
la del proveedor, el envío a RabbitMQ). Cada servicio propaga el contexto (`traceId` y el span
padre) en la cabecera W3C `traceparent`, y todos envían sus spans a **Zipkin**, que reconstruye
el árbol completo.

**Implementación.**

* `micrometer-tracing-bridge-brave` (Micrometer Tracing sobre Brave) y `zipkin-reporter-brave` en
  gateway, auth, pedidos y clientes.
* `management.tracing.sampling.probability: ${TRACING_PROBABILIDAD:1.0}` (100 % en local; en
  producción, una fracción) y `management.zipkin.tracing.endpoint: ${ZIPKIN_URL}`.
* `spring.reactor.context-propagation: auto`: el contexto de traza viaja por los operadores de
  Reactor (WebFlux cambia de hilo).
* **Llamadas salientes**: los `WebClient` (clientes y proveedor) y el `RestTemplate` se crean con
  el `ObservationRegistry`; sin él no propagan la traza.
* **Mensajería**: el binder de RabbitMQ activa la observación del `RabbitTemplate` y del listener
  si hay un `ObservationRegistry`: el `traceparent` viaja en las cabeceras del mensaje y el
  consumo en `pedidos-cliente` es un span de la misma traza que la confirmación.
* `docker-compose.yml`: servicio `zipkin` (`openzipkin/zipkin:3.4.2`, UI en <http://localhost:9411/>).

**Dos defectos que destapó la instrumentación** (y que los tests fijan):

1. Los `WebClient` se creaban con `WebClient.builder()` estático, sin observación: la traza se
   cortaba en la primera llamada saliente.
2. `PedidoService.sinCancelacion()` (que impide que una desconexión del cliente corte la
   confirmación) suscribía con `toFuture()` sin el Contexto de Reactor: la llamada al proveedor
   empezaba una **traza nueva**. Ahora reescribe el contexto y es un span hijo.

**Sin Zipkin** (tests, E2E local) el exportador escribe un único aviso
(`Spans were dropped... All subsequent errors will be logged at FINE level`) y el servicio sigue.

### 5.2 Logs con traza y correlación

**Qué resuelve.** Con varias peticiones concurrentes y seis servicios, encontrar las líneas de
una petición concreta es imposible sin un identificador común.

**Implementación.**

* Cada línea lleva `[servicio,traceId,spanId,X-Correlation-Id]`:
  `logging.pattern.correlation` en los servicios con el patrón de Boot, y el patrón de consola
  propio de `pedidos-api` con los campos del MDC (`%X{traceId}`...), porque
  `logging.pattern.correlation` solo lo aplica el patrón por defecto.
* El **`X-Correlation-Id`** (lo genera el gateway si el cliente no lo envía) se declara como
  *baggage* (`management.tracing.baggage.remote-fields` y `correlation.fields`): Micrometer
  Tracing lo propaga entre servicios y lo pone en el MDC. Es el identificador "de negocio" que se
  puede dar a un cliente; el `traceId` es el técnico.

Ejemplo real (E2E):

```
21:42:17.998 INFO  [pedidos-api,6af4d3297e3f68a4a913933366cc9a36,540e8287bb037d7e,98dc4550-b5b6-4669-b4d8-6318ffeb6e5c] [reactor-http-epoll-5] c.b.p.api.aplicacion.PedidoService - Pedido 2 creado para el cliente 1 con importe 111.48 EUR
```

**Siguiente paso** (no implementado): logs en JSON (`logging.structured.format`, Spring Boot 3.4)
y un agregador (Loki o Elasticsearch) que indexe `traceId`.

### 5.3 Métricas: Micrometer y Prometheus

**Qué es.** **Micrometer** es la fachada de métricas de Spring (como SLF4J para los logs);
**Prometheus** las recoge periódicamente (*pull*) del endpoint `/actuator/prometheus` de cada
servicio y permite consultarlas con PromQL.

**Implementación.**

* `micrometer-registry-prometheus` en gateway, auth, pedidos y clientes; `prometheus` en
  `management.endpoints.web.exposure.include`.
* Etiqueta común `application=<servicio>` (`management.metrics.tags`).
* Histograma de latencia HTTP (`percentiles-histogram.http.server.requests`): permite calcular
  p95/p99 con `histogram_quantile`.
* `docker/prometheus/prometheus.yml`: recoge los cuatro servicios cada 15 s; UI en
  <http://localhost:9090/>.

| Métrica | Qué mide |
|---|---|
| `http_server_requests_seconds_*` | Latencia y códigos de respuesta de cada endpoint |
| `spring_cloud_gateway_requests_seconds_count` / `_sum` | Peticiones y tiempo por ruta (`routeId`) y resultado en el gateway |
| `resilience4j_circuitbreaker_state` / `_failure_rate` | Estado y tasa de fallos de cada circuito (gateway y `pedidos-api`) |
| `resilience4j_ratelimiter_available_permissions` | Margen de los limitadores |
| `resilience4j_bulkhead_available_concurrent_calls` | Llamadas concurrentes libres hacia el proveedor |
| `pedidos_eventos_publicados_total{resultado}` | Eventos publicados / fallidos (propia) |
| `clientes_eventos_recibidos_total{resultado}` | Eventos procesados / duplicados (propia) |
| `jvm_*`, `process_*`, `r2dbc_pool_*` | Memoria, hilos, CPU, pool de conexiones |

Consultas útiles (en <http://localhost:9090/graph>):

```promql
# p99 de la confirmación de pedidos
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/pedidos/{id}/confirmacion"}[5m])))
# Circuitos abiertos
resilience4j_circuitbreaker_state{state="open"} == 1
# Eventos que no se han podido publicar
increase(pedidos_eventos_publicados_total{resultado="error"}[15m])
```

**Seguridad.** `/actuator/prometheus` es público (no contiene datos de negocio) para que
Prometheus lo recoja desde la red interna. En producción iría en un puerto de gestión propio
(`management.server.port`) no expuesto fuera de la red.

---

## 6. Spring Boot Actuator

**Qué es.** Endpoints de operación que Spring Boot añade a cada servicio: salud, información,
métricas, etc.

| Servicio | Endpoints expuestos | Particularidades |
|---|---|---|
| `pedidos-gateway` | `health`, `info`, `metrics`, `prometheus` | *Probes* `liveness`/`readiness` |
| `pedidos-api` | `health`, `info`, `metrics`, `prometheus` | *Probes*; salud de R2DBC; detalle solo autenticado |
| `pedidos-cliente` | `health`, `info`, `metrics`, `prometheus` | *Probes* |
| `pedidos-auth` | `health`, `info`, `metrics`, `prometheus` | `health` público (antes lo protegía el login y el contenedor nunca pasaba a `healthy`) |
| `pedidos-registry` | `health`, `info` | |
| `pedidos-config` | `health`, `info` | `/encrypt` y `/decrypt` son del Config Server, no de Actuator |

**Para qué se usa en el sistema.**

* **Healthchecks de Docker**: cada contenedor consulta `/actuator/health` y el compose encadena el
  arranque con `depends_on: condition: service_healthy`.
* ***Probes* de Kubernetes**: `/actuator/health/liveness` (¿reiniciar?) y
  `/actuator/health/readiness` (¿enviar tráfico?).
* **Métricas** para Prometheus (§5.3).
* Qué entra en la salud está decidido: la base de datos sí (sin ella el servicio no funciona); el
  broker de mensajes no (§4.3).

---

## 7. Persistencia: R2DBC y por qué no JPA

### 7.1 Qué se usa

**R2DBC** (*Reactive Relational Database Connectivity*) con `DatabaseClient` y SQL explícito, sobre
H2 en modo PostgreSQL (desarrollo y tests) y **PostgreSQL** (perfil `postgres`, compose). Es la
especificación reactiva de acceso a bases de datos relacionales: las consultas no bloquean el
hilo.

### 7.2 Por qué no JPA / Jakarta Persistence

**JPA** (*Jakarta Persistence API*, antes *Java Persistence API*) es la especificación de mapeo
objeto-relacional de Jakarta EE; Hibernate es su implementación habitual y Spring Data JPA, la
capa de repositorios. Se descarta por tres motivos:

1. **Es bloqueante.** JPA se apoya en JDBC: cada consulta ocupa el hilo hasta que la base de datos
   responde. En WebFlux eso bloquearía el *event loop* (unos pocos hilos para todas las
   peticiones); habría que desviar cada consulta a un pool de hilos aparte y se perdería el motivo
   de usar reactivo (§9.2 de DECISIONES).
2. **El compare-and-set necesita SQL explícito.** La exclusión mutua de la confirmación es un
   `UPDATE ... WHERE id = ? AND estado = ? AND version = ?` que devuelve las filas afectadas. Con
   JPA se haría con `@Version` y `OptimisticLockException`, que detecta el conflicto **al hacer
   flush**, después de haber cargado y modificado la entidad; el `UPDATE` condicional decide en
   una sola sentencia atómica y no necesita leer antes.
3. **El modelo de dominio es Java puro.** `Pedido` es inmutable y no lleva anotaciones de
   persistencia; JPA exige entidades mutables con constructor vacío. El mapeo está en
   `PedidoMapper`.

**Lo que sí se usa de Jakarta EE:** **Jakarta Validation** (`jakarta.validation`: `@NotBlank`,
`@Email`, `@Valid` en los DTO de entrada) y **Jakarta Servlet** en `pedidos-auth` (Spring
Authorization Server corre sobre Spring MVC).

**Cuándo sí sería JPA.** Con Java 21 e hilos virtuales, un servicio en Spring MVC + Spring Data JPA
escalaría de forma parecida con código imperativo; si el equipo viene de ese stack, es una
alternativa razonable. El dominio no depende de R2DBC (los puertos devuelven `Mono`, pero el
modelo es Java puro), así que el cambio afectaría a los adaptadores, no a la lógica.

---

## 8. Clientes HTTP: WebClient y por qué no OpenFeign

### 8.1 Qué se usa

**`WebClient`** (reactivo, sobre Reactor Netty) para las dos llamadas salientes de `pedidos-api`:
al servicio de clientes (`@LoadBalanced`) y al proveedor logístico (con timeouts de conexión y de
respuesta). Existe además un adaptador con **`RestTemplate`** para el proveedor
(`app.proveedor.modo=bloqueante`), que comparte la política de resiliencia y la batería de tests.

### 8.2 Por qué no OpenFeign

**OpenFeign** genera un cliente HTTP a partir de una interfaz anotada (`@FeignClient`), integrado
con Eureka y LoadBalancer. Es cómodo, pero:

* **Es bloqueante**: Spring Cloud OpenFeign no admite tipos reactivos (`Mono`/`Flux`); el propio
  proyecto lo indica y remite a alternativas. En WebFlux habría que desviar cada llamada a otro pool.
* **La resiliencia del proveedor necesita control fino** (timeout por intento y presupuesto
  global, qué se reintenta, qué abre el circuito, `Retry-After`) que aquí se expresa con
  operadores de Reactor sobre el `WebClient`.

**Alternativa declarativa compatible** (si se quisiera el estilo de Feign): las **HTTP Interfaces**
de Spring 6 (`@HttpExchange` + `HttpServiceProxyFactory` sobre el mismo `WebClient`), que sí
devuelven `Mono`. Para dos llamadas no compensa la indirección.

---

## 9. Verlo funcionando

```bash
docker compose up --build -d
```

| Herramienta | URL | Qué mirar |
|---|---|---|
| Consola de la aplicación | <http://localhost:8080/> | Crear y confirmar pedidos |
| Eureka | <http://localhost:8761/> | Instancias registradas de cada servicio |
| Zipkin | <http://localhost:9411/> | *Run query* → una traza de `POST /api/pedidos/{id}/confirmacion`: gateway → pedidos-api → proveedor y RabbitMQ → pedidos-cliente |
| Prometheus | <http://localhost:9090/> | *Status → Targets* (los 4 servicios `UP`) y las consultas de §5.3 |
| RabbitMQ | <http://localhost:15672/> (guest/guest) | *Exchanges* → `pedidos.confirmados`; *Queues* → `pedidos.confirmados.pedidos-cliente` y su `.dlq` |

Para ver los mecanismos de fallo:

```bash
docker compose stop pedidos-cliente     # el gateway: 503 en /api/clientes/** y la ruta de pedidos sigue
docker compose stop rabbitmq            # confirmar sigue respondiendo 200; pedidos_eventos_publicados_total{resultado="error"} sube
docker compose start rabbitmq pedidos-cliente
```

La salida real de estas pruebas está en [EVIDENCIA.md](../documentacion/EVIDENCIA.md) §6.
