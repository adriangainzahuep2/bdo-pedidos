# Evidencia de verificación

Salida real de las verificaciones, ejecutadas con **JDK 21** como JVM de ejecución (el código
compila con `--release 17`, que es la versión que exige el enunciado) y PostgreSQL 16.
Todo es reproducible con los comandos de cada apartado.

La verificación de Docker (§4) se ha ejecutado además con **Java 17** dentro de las imágenes
(`eclipse-temurin:17`), que es la versión de runtime que usa el despliegue.

---

## 1. Tests unitarios y de integración

```bash
./gradlew clean build
```

```
BUILD SUCCESSFUL
160 tests, 0 fallos, 0 errores, 0 omitidos
```

| Módulo | Suite | Tests | Fallos | Omitidos |
|---|---|---:|---:|---:|
| `pedidos-dominio` | Excepciones tipadas del dominio | 5 | 0 | 0 |
| `pedidos-dominio` | PedidoConfirmado - evento de dominio | 2 | 0 | 0 |
| `pedidos-dominio` | EstadoPedido - maquina de estados del pedido | 5 | 0 | 0 |
| `pedidos-dominio` | Pedido - agregado raiz y sus invariantes | 6 | 0 | 0 |
| `pedidos-dominio` | Cliente | 2 | 0 | 0 |
| `pedidos-dominio` | IntentoConfirmacion | 6 | 0 | 0 |
| `pedidos-dominio` | ResultadoTarifa | 2 | 0 | 0 |
| `pedidos-dominio` | SolicitudTarifa | 5 | 0 | 0 |
| `pedidos-dominio` | Tarifa | 4 | 0 | 0 |
| `pedidos-dominio` | importe de productos | 5 | 0 | 0 |
| `pedidos-dominio` | importe total | 3 | 0 | 0 |
| `pedidos-dominio` | peso y bultos | 2 | 0 | 0 |
| `pedidos-dominio` | CalculadoraTotalPedido - calculo unitario del total | 5 | 0 | 0 |
| `pedidos-api` | PedidoService - orquestacion del caso de uso | 13 | 0 | 0 |
| `pedidos-api` | ClienteServiceClient - integracion con el servicio de clientes simulado | 4 | 0 | 0 |
| `pedidos-api` | Eventos - publicacion de PedidoConfirmado con Spring Cloud Stream | 2 | 0 | 0 |
| `pedidos-api` | PublicadorEventosStream - publicacion de mejor esfuerzo | 3 | 0 | 0 |
| `pedidos-api` | PedidoRepository - persistencia y transiciones atomicas | 5 | 0 | 0 |
| `pedidos-api` | ProveedorTarifasClientBloqueante - integracion contra el proveedor simulado | 6 | 0 | 0 |
| `pedidos-api` | ProveedorTarifasClientReactivo - integracion contra el proveedor simulado | 16 | 0 | 0 |
| `pedidos-api` | Observabilidad - trazas distribuidas, correlacion en logs y metricas Prometheus | 4 | 0 | 0 |
| `pedidos-api` | API de pedidos - adaptador bloqueante (RestTemplate) | 2 | 0 | 0 |
| `pedidos-api` | API de pedidos - flujo completo con proveedor simulado | 17 | 0 | 0 |
| `pedidos-api` | API de pedidos - timeout del proveedor | 1 | 0 | 0 |
| `pedidos-auth` | Servidor de autorizacion - client_credentials, authorization_code + PKCE y CORS | 9 | 0 | 0 |
| `pedidos-cliente` | Eventos - consumo de PedidoConfirmado y resumen por cliente | 4 | 0 | 0 |
| `pedidos-cliente` | API de clientes - alta, consulta y control de acceso | 6 | 0 | 0 |
| `pedidos-gateway` | API Gateway - circuit breaker por microservicio | 7 | 0 | 0 |
| `pedidos-gateway` | API Gateway - autenticacion, correlacion y enrutado | 6 | 0 | 0 |
| `pedidos-gateway` | API Gateway - limite de peticiones | 1 | 0 | 0 |
| `pedidos-gateway` | API Gateway - trazas distribuidas y metricas | 2 | 0 | 0 |

Los tests que pide el enunciado:

* **Test unitario del total**: suites de `CalculadoraTotalPedidoTest` (`pedidos-dominio`, sin Spring).
* **Test de integración con error o timeout del proveedor**: `ProveedorTarifasClientReactivoTest`
  y `ProveedorTarifasClientBloqueanteTest` (WireMock), y a nivel de API `PedidoControllerTest` y
  `PedidoControllerTimeoutTest`.

### Cobertura (JaCoCo)

| Módulo | Líneas | Ramas | Instrucciones |
|---|---:|---:|---:|
| `pedidos-dominio` | 88,1 % | 67,7 % | 85,2 % |
| `pedidos-api` | 92,4 % | 75,5 % | 92,6 % |
| `pedidos-cliente` | 98,6 % | 70,0 % | 98,6 % |
| `pedidos-gateway` | 95,2 % | 80,0 % | 97,4 % |
| `pedidos-auth` | 95,0 % | 80,0 % | 95,3 % |

Informes: `pedidos-*/build/reports/jacoco/test/jacoco.xml` (el que consume Sonar) y
`pedidos-*/build/reports/jacoco/test/html/`.

### Tests de JavaScript de la consola web

```bash
./gradlew :pedidos-api:testJavaScript
```

La lógica PKCE (`static/pkce.js`) se prueba con `node --test` contra el vector del RFC 7636
(verificador conocido, reto `S256` y su `code_challenge` esperado). La tarea se salta si Node.js no
está en el `PATH`, para que el build de Java no dependa de Node.

---

## 1 bis. Extremo a extremo: la consola web

El E2E levanta el stack completo y comprueba la consola **a través del gateway**:

```
[ OK ] Compilacion correcta
[ OK ] La consola se sirve sin token en http://localhost:8080/ (index.html, app.js, pkce.js, styles.css)
[ OK ] Tras el login, /oauth2/authorize devuelve un codigo a la consola
[ OK ] El codigo se canjea con el verificador PKCE, sin secreto y con CORS para la consola
[ OK ] Con el token del usuario la consola consulta pedidos a traves del gateway
========== VERIFICACION DE EXTREMO A EXTREMO CORRECTA ==========
```

Es el mismo flujo que hace el navegador (formulario de login con CSRF, `authorization_code` con
desafío `S256` y canje del código con el verificador desde el origen de la consola), ejecutado por
script: si el `app.js` y el servidor dejaran de estar de acuerdo, el E2E falla.

---

## 2. Esquema PostgreSQL con 10 filas por tabla

```bash
./scripts/probar-postgresql.sh
```

```
[pg] PostgreSQL: postgres (PostgreSQL) 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1)
[pg] cluster temporal arrancado (socket en /tmp/bdo-pg.VlbOsf, puerto 34943)
[pg] esquema.sql aplicado
[pg] datos-prueba.sql cargado
  OK  cliente tiene 10 filas
  OK  pedido tiene 10 filas
  OK  pedido_linea tiene 10 filas
  OK  pedido_confirmacion_intento tiene 10 filas
  OK  importe_productos = suma de importe_linea en todos los pedidos
  OK  peso_total_kg = suma de cantidad x peso_unitario_kg
  OK  importe_total = productos + envio solo en pedidos CONFIRMADO
  OK  redondeo HALF_UP por linea (1,005 -> 1,01; 37,485 -> 37,49)
  OK  suma exacta de los totales confirmados = 1397.26
  OK  cada pedido CONFIRMADO tiene un unico intento OK con su coste
  OK  num_intentos_confirmacion = intentos fallidos auditados
  OK  la instantanea nombre/email de cada pedido coincide con su cliente
  OK  hay pedidos en los 4 estados (PENDIENTE, CONFIRMANDO, CONFIRMADO, CANCELADO)
  OK  rechazado: email de cliente duplicado
  OK  rechazado: cliente sin nombre
  OK  rechazado: estado inexistente
  OK  rechazado: pedido CONFIRMADO sin coste de envio ni total
  OK  rechazado: pedido PENDIENTE con coste y total
  OK  rechazado: total que no cuadra con productos + envio
  OK  rechazado: pedido CONFIRMANDO sin fecha de inicio (lease)
  OK  rechazado: pedido con cero bultos
  OK  rechazado: importe negativo
  OK  rechazado: linea con importe mal redondeado (1,00 en lugar de 1,01)
  OK  rechazado: linea con cantidad cero
  OK  rechazado: linea de un pedido inexistente
  OK  rechazado: intento OK sin coste de envio
  OK  rechazado: intento con numero de llamadas negativo
  OK  rechazado: intento con resultado inexistente
  OK  compare-and-set: solo una de dos confirmaciones toma el pedido
  OK  confirmacion en tres fases: total 91,98 + 7,95 = 99,93
  OK  la recuperacion devuelve a PENDIENTE el pedido con el lease caducado
  OK  borrar un pedido borra sus lineas y sus intentos (ON DELETE CASCADE)
========== VERIFICACION DE POSTGRESQL CORRECTA ==========
[pg] DDL de los servicios (y data.sql de demostracion) aplicados en bases separadas
[pg] OK  el esquema consolidado y los DDL de los servicios son identicos (67 columnas y restricciones)
[pg] ========== PRUEBA DE POSTGRESQL CORRECTA ==========
```

---

## 3. Extremo a extremo sobre PostgreSQL

```bash
E2E_BD=postgres ./scripts/verificar-e2e.sh
```

Levanta los servicios reales (autorización, Eureka, clientes, pedidos y gateway), el proveedor
simulado (que exige el token) y un PostgreSQL desechable con una base de datos por servicio, y
comprueba los flujos **a través del API Gateway** con un token OAuth2 real.

```
[ OK ] Compilacion correcta
[ OK ] Bases de datos H2 en memoria (E2E_BD=postgres para usar PostgreSQL)
[ OK ] servidor de autorizacion responde en http://localhost:9000/actuator/health (en 10 s)
[ OK ] registro de servicios responde en http://localhost:8761/actuator/health (en 2 s)
[ OK ] microservicio de clientes responde en http://localhost:8082/actuator/health (en 13 s)
[ OK ] microservicio de pedidos responde en http://localhost:8081/actuator/health (en 1 s)
[ OK ] API Gateway responde en http://localhost:8080/actuator/health (en 0 s)
[ OK ] El servicio PEDIDOS-API esta registrado en Eureka
[ OK ] El servicio PEDIDOS-CLIENTE esta registrado en Eureka
[ OK ] El servicio PEDIDOS-GATEWAY esta registrado en Eureka
[ OK ] Token obtenido (823 caracteres)
[ OK ] El gateway enruta a microservicio de clientes
[ OK ] El gateway enruta a microservicio de pedidos
[ OK ] El servicio de pedidos llega al de clientes (cliente inexistente -> 422)
[ OK ] 401 sin token
[ OK ] Cliente creado con id 1
[ OK ] Importe calculado por el servidor: 111.48
[ OK ] Pedido confirmado, coste=9.98, total=121.46
[ OK ] La segunda confirmacion no ha vuelto a llamar al proveedor (1 -> 1)
[ OK ] Una confirmacion devuelve 200 y la otra 409 (200 409 )
[ OK ] Solo UNA llamada nueva al proveedor (0 -> 1)
[ OK ] El pedido queda CONFIRMADO con un unico coste de envio (8.45)
[ OK ] El proveedor devuelve 503 y la API responde 502
[ OK ] El pedido sigue en PENDIENTE (reintentable)
[ OK ] La consola se sirve sin token en http://localhost:8080/ (index.html, app.js, pkce.js, styles.css)
[ OK ] Tras el login, /oauth2/authorize devuelve un codigo a la consola
[ OK ] El codigo se canjea con el verificador PKCE, sin secreto y con CORS para la consola
[ OK ] Con el token del usuario la consola consulta pedidos a traves del gateway
========== VERIFICACION DE EXTREMO A EXTREMO CORRECTA ==========
```

La misma verificación sobre H2 (`./scripts/verificar-e2e.sh`) y sobre PostgreSQL
(`E2E_BD=postgres ./scripts/verificar-e2e.sh`) da el mismo resultado: **28 comprobaciones
correctas y 0 fallidas** (el script cuenta los fallos y sale con ese número como código de
salida).

---

## 4. Docker Compose

```bash
cp .env.example .env      # rellenar los secretos
docker compose up --build
```

### Arranque real verificado

El arranque completo del stack **sí** está verificado en ejecución: los ocho servicios (los seis
de la aplicación, PostgreSQL y el proveedor logístico simulado) arrancan, pasan a `healthy` y
sirven tráfico real a través del gateway.

```bash
$ docker compose ps
NAME                                IMAGE                        SERVICE               STATUS
bdo-pedidos-pedidos-api-1           bdo/pedidos-api:1.0.0        pedidos-api           Up 7 minutes (healthy)
bdo-pedidos-pedidos-auth-1          bdo/pedidos-auth:1.0.0       pedidos-auth          Up 50 seconds (healthy)
bdo-pedidos-pedidos-cliente-1       bdo/pedidos-cliente:1.0.0    pedidos-cliente       Up 7 minutes (healthy)
bdo-pedidos-pedidos-config-1        bdo/pedidos-config:1.0.0     pedidos-config        Up 7 minutes (healthy)
bdo-pedidos-pedidos-gateway-1       bdo/pedidos-gateway:1.0.0    pedidos-gateway       Up 5 minutes (healthy)
bdo-pedidos-pedidos-registry-1      bdo/pedidos-registry:1.0.0   pedidos-registry      Up 7 minutes (healthy)
bdo-pedidos-postgres-1              postgres:16-alpine           postgres              Up 7 minutes (healthy)
bdo-pedidos-proveedor-logistico-1   python:3.12-alpine           proveedor-logistico   Up 7 minutes
```

| Qué se comprueba | Resultado |
|---|---|
| `docker compose config` resuelve todas las variables de `.env` y los servicios | correcto |
| Los ficheros montados existen (`scripts/postgres-init.sh`, los dos `schema.sql`, `mock-proveedor.py`) | correcto |
| El DDL que aplica el compose es el mismo que usan los servicios (`classpath:db/schema.sql`) | correcto |
| Los seis `bootJar` que copia el `Dockerfile` se construyen con el nombre y la versión esperados | correcto |
| Los ocho contenedores arrancan y pasan a `healthy` | correcto |
| `scripts/postgres-init.sh` crea las dos bases (`pedidos` y `clientes`) y aplica su DDL | correcto |
| Eureka registra los tres servicios de negocio (`PEDIDOS-API`, `PEDIDOS-CLIENTE`, `PEDIDOS-GATEWAY`) | correcto |
| La consola web se sirve por el gateway (`index.html` y `app.js`, HTTP 200) | correcto |
| `GET /actuator/health` responde `UP` en los seis servicios | correcto |
| El login de la consola (`demo` + contraseña del `.env`) funciona contra el stack | correcto |

### Flujo completo contra el stack

Todo lo siguiente va **por el gateway** (`:8080`), con el token `client_credentials` emitido por
el servidor de autorización del propio stack (`:9000`):

```bash
$ curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/pedidos ...   # 401 (sin token)
$ curl -s -u pedidos-gateway:$SECRETO -d "grant_type=client_credentials&scope=..." \
      http://localhost:9000/oauth2/token                                                    # token (827 caracteres)
$ curl -s -X POST http://localhost:8080/api/clientes ...                                   # {"id":1,"nombre":"Empresa Docker S.L."}
$ curl -s -X POST http://localhost:8080/api/pedidos ...                                    # 201, PENDIENTE, importeProductos 91.98
$ curl -s -X POST http://localhost:8080/api/pedidos/1/confirmacion ...                     # CONFIRMADO, costeEnvio 8.39, total 100.37 EUR
$ curl -s -X POST http://localhost:8080/api/pedidos/1/confirmacion ...                     # 200 idempotente
```

El importe lo calcula el servidor (2 × 45,99 = 91,98) y el coste de envío llega del proveedor
simulado. En PostgreSQL:

```
 id | estado    | coste_envio | importe_total
----+-----------+-------------+---------------
  1 | CONFIRMADO|        8.39 |       100.37
```

### El login de la consola contra el stack

El mismo flujo que hace el navegador (`app.js`): formulario de login con CSRF contra el servidor
de autorización, `/oauth2/authorize` con el desafío S256 y canje del código con el verificador
desde el origen de la consola. Con el usuario `demo` y la contraseña de `AUTH_USUARIO_PASSWORD` del
`.env`:

```
1. CSRF del formulario de login: 96 caracteres
2. /oauth2/authorize devuelve codigo a la consola: 128 caracteres
3. token del usuario: 807 caracteres
   Access-Control-Allow-Origin: http://localhost:8080
4. GET /api/pedidos/1 a traves del gateway -> HTTP 200
```

Es decir: el mismo camino que recorre una persona en <http://localhost:8080/>, verificado contra
los contenedores y no solo en local.

### Resiliencia: proveedor caído y reintento

Con el proveedor **parado** (`docker compose stop proveedor-logistico`), la confirmación responde
`503` con `ProblemDetail` y el pedido **vuelve a `PENDIENTE`** con el motivo en `ultimo_error`:

```
2|PENDIENTE|No se pudo conectar con el proveedor logistico: connection timed out after 1000 ms
```

Al levantar el proveedor, la **misma** confirmación (reintentable, sin tocar nada) funciona:

```
 id | estado    | coste_envio | importe_total
----+-----------+-------------+---------------
  2 | CONFIRMADO|        5.54 |        25.04
```

Y la tabla de auditoría deja el rastro completo: tres intentos fallidos y un acierto.

```
 pedido_id | resultado | coste_envio | intentos_proveedor
-----------+-----------+-------------+--------------------
         1 | OK        |        8.39 |                  1
         2 | ERROR     |             |                  3
         2 | OK        |        5.54 |                  1
```

### Dos defectos encontrados por esta verificación

1. **`pedidos-auth` nunca llegaba a `healthy`.** Su `/actuator/health` respondía `302` (lo
   protege el login del servidor de autorización), así que el *healthcheck* del `Dockerfile` —que
   busca `"status":"UP"`— no podía pasar nunca y el contenedor aparecía como `unhealthy` estando
   arrancado. La API y el servicio de clientes ya publicaban la salud; ahora el servidor de
   autorización hace lo mismo (`AuthorizationServerConfig`, `permitAll` sobre
   `/actuator/health`), con un test que lo fija (`AuthorizationServerIntegrationTest.saludPublica`).
2. **El arranque fallaba sin `.env`, y es lo correcto.** Sin secretos, el servidor de autorización
   no arranca (`auth.password` en blanco) ni el de pedidos (`app.proveedor.token` en blanco): es un
   fallo en el arranque, no un 500 en la primera petición. Con `.env` completo, los ocho servicios
   arrancan.

> Requisito del entorno: los *healthcheck* consultan `DiskSpaceHealthIndicator`, que marca `DOWN`
> si el disco tiene menos de 10 MB libres. Con el disco del host al 100 % el arranque se queda
> esperando, aunque el código esté bien.

> El arranque de los mismos servicios **también** está verificado por otra vía: el E2E levanta el
> stack real en local (`scripts/verificar-e2e.sh`), con el proveedor simulado y el gateway delante.

---

## 4 bis. CI/CD (Jenkins, SonarQube, Gitea)

El pipeline **se ha ejecutado de verdad** en local, con las tres piezas levantadas en Docker
(`docker-compose.ci.yml` y `scripts/verificar-ci.sh`). La primera ejecución completa en verde es
la **build #10** (9 de noviembre de 2026, sobre `main` en `ffcaa9d`). Las nueve anteriores no
llegaron al final: se abortaron por el timeout global o fallaron, y cada una destapó uno de los
defectos de la lista de abajo; la #9 pasó el *quality gate* y las imágenes y cayó en el E2E (defecto 7).
La **build #11** repite el resultado sobre el `main` actual (`3a21225`, con el arreglo del defecto 7
ya fusionado y el test del *lease*); su salida es la de abajo.

| Qué | Estado |
|---|---|
| Las 10 etapas del `Jenkinsfile` | **verificado**: builds #10 y #11, `Finished: SUCCESS` |
| Análisis de SonarQube y *quality gate* | **verificado**: `Quality gate is 'OK'` |
| *Webhook* de SonarQube hacia Jenkins | **verificado**: el evento llega y cierra el *quality gate* |
| Config Server con backend de Gitea (`CONFIG_PROFILE=git`) | **verificado**: sirve el repo clonado |
| Etapa de imágenes Docker (6 imágenes etiquetadas con el número de build) | **verificado** |
| Etapa de extremo a extremo dentro del pipeline | **verificado** |

### Cómo se levanta

```bash
./scripts/verificar-ci.sh
```

El script levanta SonarQube (`:9001`), Gitea (`:3000`) y Jenkins (`:8091`), crea en Gitea el
usuario `bdo` y el repositorio `bdo/config-repo` con el contenido de `config-repo/`, genera el
token de API de SonarQube, registra el *webhook* hacia Jenkins, lanza el pipeline y sigue el log.

El agente de Jenkins es una imagen propia (`docker/ci/jenkins/Dockerfile`) con JDK 17, Node 20,
Python 3, los binarios de PostgreSQL y el CLI de Docker, porque el pipeline los necesita todos.

### Salida real del pipeline (build #11)

```
Checking out Revision 3a21225bdbf000921480b9fb2d77233ad0f78070 (refs/remotes/origin/main)
[Pipeline] { (Checkout)
[Pipeline] { (Build)
[Pipeline] { (Esquema PostgreSQL)
========== VERIFICACION DE POSTGRESQL CORRECTA ==========
[Pipeline] { (Tests unitarios e integracion)      informe JUnit: 136 correctos, 0 fallos, 0 omitidos
[Pipeline] { (Cobertura (JaCoCo))
[Pipeline] { (Analisis de calidad (SonarQube))
[Pipeline] { (Quality gate)
SonarQube task 'AaHCK3zY7u9of3Ob9lEg' completed. Quality gate is 'OK'
[Pipeline] { (Empaquetado)
[Pipeline] { (Imagenes Docker)
Successfully tagged bdo/pedidos-api:1.0.0-11
Successfully tagged bdo/pedidos-cliente:1.0.0-11
Successfully tagged bdo/pedidos-auth:1.0.0-11
Successfully tagged bdo/pedidos-gateway:1.0.0-11
Successfully tagged bdo/pedidos-registry:1.0.0-11
Successfully tagged bdo/pedidos-config:1.0.0-11
[Pipeline] { (Verificacion de extremo a extremo)
[ OK ] API Gateway responde en http://localhost:8080/actuator/health (en 0 s)
[ OK ] Una confirmacion devuelve 200 y la otra 409 (200 409 )
[ OK ] Solo UNA llamada nueva al proveedor (0 -> 1)
[ OK ] El proveedor devuelve 503 y la API responde 502
[ OK ] El codigo se canjea con el verificador PKCE, sin secreto y con CORS para la consola
========== VERIFICACION DE EXTREMO A EXTREMO CORRECTA ==========
Finished: SUCCESS
```

La build #11 tardó 5 min 39 s (la #10, 2 min 9 s): las capas de dependencias ya estaban en la
caché del host y solo se recompilaron los JAR de cada imagen; desde
cero, las seis imágenes tardan unos 25 minutos (de ahí el timeout de 90).

### Defectos que ha destapado la ejecución real

1. **La cobertura de Sonar no se encontraba.** La ruta apuntaba al informe de la raíz del
   proyecto, pero cada módulo genera el suyo en su propio directorio de build. El scanner lo
   avisaba (`No coverage report can be found with
   sonar.coverage.jacoco.xmlReportPaths='build/reports/jacoco/test/jacoco.xml'`) y analizaba sin
   cobertura. Ahora se usa `**/build/reports/jacoco/test/jacoco.xml`.
2. **Declarar `sonar.sources` hacía que cada fichero se indexara dos veces** (`can't be indexed
   twice`): el plugin de Gradle aplicado en la raíz ya agrega el análisis de todos los módulos.
3. **Elasticsearch bloqueaba el análisis** con el disco del host por encima del 95 %
   (`cluster_block_exception ... disk usage exceeded flood-stage watermark`). El ajuste no es una
   propiedad de la JVM sino del clúster, y además hay que quitar el bloqueo ya puesto en los
   índices. Lo hace `scripts/verificar-ci.sh` al arrancar.
4. **La ruta del *webhook* no es la intuitiva**: el plugin `sonar` publica el receptor en
   `/sonarqube-webhook/`, no en `/sonar-webhook/`. Con la ruta equivocada el *quality gate* se
   quedaba esperando hasta agotar el tiempo.
5. **Al agente le faltaba el CLI de Docker** (tenía el socket, pero no el cliente): la etapa de
   imágenes fallaba con `docker: not found`.
6. **El Config Server usaba siempre el backend `native`**: declarar
   `spring.cloud.config.server.native.search-locations` sin perfil activa ese repositorio
   siempre, y el backend de git no llega a crearse. Con la búsqueda dentro de su perfil, el
   Config Server clona el repositorio de Gitea.
7. **El API Gateway del E2E no arrancaba dentro de Jenkins.** El E2E corre en el contenedor de
   Jenkins y levanta el gateway en el 8080, pero ese puerto era el del propio Jenkins (el
   `8091:8080` del compose evitaba el choque en el host, no dentro del contenedor). La build #9
   pasó el *quality gate* y las seis imágenes y cayó ahí: `API Gateway NO responde en
   http://localhost:8080/actuator/health (tras 184 s)`. Ahora Jenkins escucha en el 8095 de su
   contenedor (`JENKINS_OPTS=--httpPort=8095`) y el *webhook* de SonarQube apunta a ese puerto.

### Métricas del análisis

| Métrica | Valor |
|---|---:|
| Líneas de código (ncloc) | 3 109 |
| Cobertura | 84,0 % |
| Bugs | 0 |
| Vulnerabilidades | 1 (falso positivo: `java:S6437` marca el generador de tokens de *test*) |
| *Code smells* | 47 |

### Requisitos del entorno

* **Espacio en disco**: por debajo del 95 % de uso, o Elasticsearch bloquea el análisis.
* **Memoria**: SonarQube y el agente de Jenkins necesitan ~2 GB; con el E2E en marcha, seis
  servicios más. En una máquina de 8 GB hay que ajustar los `-Xmx`.
* **El usuario que ejecuta el pipeline** debe poder hablar con el socket de Docker.

---

## 5. Trazabilidad con el enunciado

| Requisito | Dónde está resuelto | Cómo se verifica |
|---|---|---|
| `POST /api/pedidos` crea el pedido con sus líneas en una transacción | `PedidoService.crear` (`TransactionalOperator`) | `PedidoServiceTest.crear`, `PedidoRepositoryTest.insertaYLee` |
| El importe se calcula en el servidor | `CrearPedidoRequest` no tiene campos de importe | `PedidoControllerTest.ignoraElImporteDelCliente` |
| `POST /api/pedidos/{id}/confirmacion` guarda el coste y actualiza el total | `PedidoService.confirmar` | `PedidoControllerTest.confirmaElPedido`, E2E |
| Contrato exacto del proveedor (ruta, Bearer, cuerpo) | `ProveedorTarifasClientReactivo` / `...Bloqueante` | `ProveedorTarifasClient*Test.devuelveLaTarifa` (cabeceras y cuerpo con WireMock) |
| Puede tardar hasta 5 s | Timeout por intento 6 s, presupuesto 8 s, sin reintentar timeouts | `timeoutNoSeReintenta`, `timeoutDeNettyEsTimeout`, `PedidoControllerTimeoutTest` |
| 503 intermitentes | 3 intentos con backoff y jitter, `Retry-After` dentro del presupuesto | `reintentaAnte503`, `reintentaYSeRecupera`, `respetaRetryAfter`, `retryAfterFueraDelPresupuesto` |
| Token fuera del código | `PROVEEDOR_LOGISTICO_TOKEN` (`@NotBlank`: sin él no arranca) | `application.yml`; el simulador exige el token en el E2E |
| Req. 1: comportamiento ante fallos y estado del pedido | `docs/DECISIONES.md` §5 | `falloDelProveedor`, `respuestaVaciaDelProveedor`, `confirmarConCircuitoAbierto`, E2E |
| Req. 2: dos confirmaciones simultáneas | Compare-and-set en base de datos | `dosConfirmacionesSimultaneas`, `diezConfirmacionesSimultaneas`, E2E y `verificacion.sql` |
| Req. 3: precisión decimal | `BigDecimal`, HALF_UP por línea, `NUMERIC` y `CHECK` | `CalculadoraTotalPedidoTest`, `verificacion.sql` |
| Req. 4: test unitario del total + integración con error/timeout | `CalculadoraTotalPedidoTest` + suites con WireMock | `./gradlew build` |

---

## 6. Plataforma: resiliencia, eventos asíncronos y observabilidad

Verificado el 11 de noviembre de 2026 contra el stack real de `docker compose up` (once
contenedores *healthy*: los seis servicios, PostgreSQL, RabbitMQ, Zipkin, Prometheus y el
proveedor simulado). Qué es cada pieza y cómo está implementada: [docs/PLATAFORMA.md](../docs/PLATAFORMA.md).

| Qué | Estado |
|---|---|
| Evento `PedidoConfirmado` de `pedidos-api` a `pedidos-cliente` por RabbitMQ | **verificado** |
| Consumidor idempotente (mensaje repetido a mano en el broker) | **verificado** |
| Traza única gateway → pedidos-api → proveedor → RabbitMQ → pedidos-cliente en Zipkin | **verificado** |
| `traceId` y `X-Correlation-Id` iguales en los logs de los dos servicios | **verificado** |
| Prometheus recoge los 4 servicios; circuitos, eventos y p99 consultables | **verificado** |
| Circuito del gateway: cerrado → abierto → semiabierto → cerrado, solo en su ruta | **verificado** |
| Consumidor parado: los eventos esperan en la cola y se procesan al volver | **verificado** |
| Broker parado: la confirmación responde 200, el fallo se cuenta y la salud sigue `UP` | **verificado** |
| Mensaje imposible de procesar: reintentos y cola de mensajes muertos | **verificado** |

### 6.1 Flujo y evento asíncrono

```
cliente creado: 3
pedido creado: 3
confirmacion: CONFIRMADO coste 8.39 total 100.37
X-Correlation-Id: evidencia-plataforma-1

GET /api/clientes/3/pedidos-confirmados -> {"clienteId":3,"pedidosConfirmados":1,"importeConfirmado":{"EUR":100.37}}

$ rabbitmqctl list_exchanges / list_queues name messages consumers
pedidos.confirmados                       topic
pedidos.confirmados.pedidos-cliente       0  1
pedidos.confirmados.pedidos-cliente.dlq   0  0
```

Publicación en `pedidos-api` y consumo en `pedidos-cliente`, 43 ms después, con el **mismo
`traceId` y la misma correlación** (el contexto viaja en las cabeceras del mensaje):

```
21:05:28.848 INFO  [pedidos-api,6af4d898b35c158e56fc5de55004723e,1b258a245da5ed60,evidencia-plataforma-1] [boundedElastic-4] c.b.p.a.e.PublicadorEventosStream - Evento PedidoConfirmado publicado (pedido 3)
2026-11-11T21:05:28.891Z  INFO 1 --- [pedidos-cliente] [didos-cliente-1] [pedidos-cliente,6af4d898b35c158e56fc5de55004723e,599486a5b6c11311,evidencia-plataforma-1] c.b.p.c.eventos.ConsumidorEventosConfig  : PedidoConfirmado 3 registrado para el cliente 3 (100.37 EUR)
```

**Idempotencia**: el mismo evento, publicado otra vez a mano en el exchange (`rabbitmqadmin
publish`), se ignora y el resumen no cambia:

```
[pedidos-cliente,6af4d89b168c071fd94500286856d3ea,d94500286856d3ea,] ... PedidoConfirmado 3 repetido: ya estaba registrado, se ignora
resumen tras la reentrega: {"clienteId":3,"pedidosConfirmados":1,"importeConfirmado":{"EUR":100.37}}
```

### 6.2 La traza en Zipkin

`GET http://localhost:9411/api/v2/trace/6af4d898b35c158e56fc5de55004723e` (la del log anterior),
ordenada por inicio y sangrada por span padre:

```
pedidos-gateway  SERVER   http post                                          564.1 ms
  pedidos-gateway  LOCAL    security filterchain before                          7.4 ms
    pedidos-gateway  LOCAL    secured request                                    554.8 ms
      pedidos-gateway  CLIENT   http post                                          544.6 ms
        pedidos-api      SERVER   http post /api/pedidos/{id}/confirmacion           527.5 ms
          pedidos-api      LOCAL    secured request                                    494.3 ms
              pedidos-api      CLIENT   http post                                           30.8 ms   <- proveedor logistico
              pedidos-api      LOCAL    stream-bridge process                                5.1 ms
              pedidos-api      PRODUCER pedidos.confirmados/pedidos.confirmados send         3.8 ms
                pedidos-cliente  CONSUMER pedidos.confirmados.pedidos-cliente receive         39.7 ms
                  pedidos-cliente  LOCAL    pedido-confirmado process                           23.2 ms
16 spans, 3 servicios: ['pedidos-api', 'pedidos-cliente', 'pedidos-gateway']
```

(Se omiten los spans de `authorize exchange` y `security filterchain after`.) La llamada al
proveedor es hija de la confirmación: antes del arreglo de `PedidoService.sinCancelacion()`
empezaba una traza nueva (lo fija `ObservabilidadTest.propagaLaTrazaAlProveedor`).

### 6.3 Métricas en Prometheus

```
target pedidos-auth:9000 up
target pedidos-gateway:8080 up
target pedidos-api:8080 up
target pedidos-cliente:8080 up

resilience4j_circuitbreaker_state == 1
  {application: pedidos-api,     name: proveedorLogistico, state: closed}
  {application: pedidos-api,     name: servicioClientes,   state: closed}
  {application: pedidos-gateway, name: clientesApi,        state: closed}
  {application: pedidos-gateway, name: pedidosApi,         state: closed}

histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/pedidos/{id}/confirmacion"}[5m])))
  0.5149   (p99 de la confirmacion: 515 ms, con el proveedor simulado)

pedidos_eventos_publicados_total  {resultado: ok} = 11   {resultado: error} = 1    (el error: escenario 6.6)
clientes_eventos_recibidos_total  {resultado: nuevo} = 10                          (desde el reinicio del escenario 6.5)
```

Familias de Resilience4j y R2DBC expuestas por `pedidos-api`: `resilience4j_circuitbreaker_*`,
`resilience4j_bulkhead_*`, `resilience4j_ratelimiter_*` y `r2dbc_pool_*`.

### 6.4 Circuito del gateway con `pedidos-cliente` parado

`docker compose stop pedidos-cliente` y 12 peticiones a `/api/clientes/4`:

```
codigos: 503 503 503 503 503 503 503 503 503 503 503 503
ultimo cuerpo: {"type":"https://bdo.example/problemas/microservicio-no-disponible","title":"Microservicio no disponible",
  "status":503,"detail":"El servicio pedidos-cliente no esta disponible (circuito abierto); reintentelo en unos segundos",
  "instance":"/fallback/pedidos-cliente","servicio":"pedidos-cliente"}
circuito clientesApi: open | circuito pedidosApi: closed
```

Con el servicio de vuelta, tras los 15 s abierto pasa a `half_open`, y tres llamadas de prueba
correctas (`200 200 200`) lo cierran: `state="closed"`.

### 6.5 Desacoplamiento temporal: consumidor parado

Con `pedidos-cliente` parado, confirmar no depende de él:

```
confirmar 4 -> 200   confirmar 5 -> 200
pedidos.confirmados.pedidos-cliente.dlq   0  0
pedidos.confirmados.pedidos-cliente       2  0      <- 2 eventos esperando, 0 consumidores
```

Al arrancarlo, consume lo pendiente y el resumen queda al día:

```
pedidos.confirmados.pedidos-cliente       0  1
... PedidoConfirmado 4 registrado para el cliente 4 ...
... PedidoConfirmado 5 registrado para el cliente 4 ...
resumen del cliente: {"clienteId":4,"pedidosConfirmados":2,"importeConfirmado":{"EUR":50.08}}
```

### 6.6 Broker parado: la confirmación no depende de RabbitMQ

`docker compose stop rabbitmq`:

```
confirmar 6 con el broker parado -> HTTP 200
21:07:33.714 WARN  [pedidos-api,6af4d915e921e9d3f50852cb3edca27b,4c4a5638990af5aa,2336e0b8-...] c.b.p.a.e.PublicadorEventosStream - No se pudo publicar PedidoConfirmado del pedido 6: ...
pedidos_eventos_publicados_total{application="pedidos-api",evento="PedidoConfirmado",resultado="error"} 1.0
health de pedidos-api: {"status":"UP","groups":["liveness","readiness"]}
```

Es el límite conocido de la publicación de mejor esfuerzo: el pedido 6 está confirmado pero su
evento se ha perdido (y queda contado). La solución es el patrón outbox
([docs/PLATAFORMA.md](../docs/PLATAFORMA.md) §4.6).

### 6.7 Mensaje imposible de procesar: cola de mensajes muertos

Un evento de un cliente que no existe (`clienteId` 999999), publicado a mano en el exchange: la
clave foránea lo rechaza en cada intento y, agotados, el binder lo aparta:

```
pedidos.confirmados.pedidos-cliente.dlq   1  0      <- el mensaje, para revisarlo
pedidos.confirmados.pedidos-cliente       0  1      <- la cola principal sigue procesando
```

### 6.8 Requisitos del entorno

* El esquema de clientes tiene una tabla nueva (`cliente_pedido_confirmado`). Con un volumen de
  PostgreSQL anterior, aplicar el DDL (es idempotente) sin borrar datos:
  `docker compose exec -T postgres psql -U pedidos -d clientes -f /esquemas/clientes.sql`, o bien
  `docker compose down -v` para inicializarlo desde cero.
* Las imágenes se reconstruyen desde cero si cambian las dependencias (la capa común del
  `Dockerfile`).

