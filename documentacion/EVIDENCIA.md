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
135 tests, 0 fallos, 0 errores, 0 omitidos
```

| Módulo | Suite | Tests | Fallos | Omitidos |
|---|---|---:|---:|---:|
| `pedidos-dominio` | Excepciones tipadas del dominio | 5 | 0 | 0 |
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
| `pedidos-api` | PedidoRepository - persistencia y transiciones atomicas | 4 | 0 | 0 |
| `pedidos-api` | ProveedorTarifasClientBloqueante - integracion contra el proveedor simulado | 6 | 0 | 0 |
| `pedidos-api` | ProveedorTarifasClientReactivo - integracion contra el proveedor simulado | 16 | 0 | 0 |
| `pedidos-api` | API de pedidos - adaptador bloqueante (RestTemplate) | 2 | 0 | 0 |
| `pedidos-api` | API de pedidos - flujo completo con proveedor simulado | 17 | 0 | 0 |
| `pedidos-api` | API de pedidos - timeout del proveedor | 1 | 0 | 0 |
| `pedidos-auth` | AuthorizationServerIntegrationTest - authorization_code + PKCE del cliente publico | 9 | 0 | 0 |
| `pedidos-cliente` | API de clientes - alta, consulta y control de acceso | 6 | 0 | 0 |
| `pedidos-gateway` | API Gateway - autenticacion, correlacion, enrutado y consola web | 6 | 0 | 0 |
| `pedidos-gateway` | API Gateway - limite de peticiones | 1 | 0 | 0 |

Los tests que pide el enunciado:

* **Test unitario del total**: suites de `CalculadoraTotalPedidoTest` (`pedidos-dominio`, sin Spring).
* **Test de integración con error o timeout del proveedor**: `ProveedorTarifasClientReactivoTest`
  y `ProveedorTarifasClientBloqueanteTest` (WireMock), y a nivel de API `PedidoControllerTest` y
  `PedidoControllerTimeoutTest`.

### Cobertura (JaCoCo)

| Módulo | Líneas | Ramas | Instrucciones |
|---|---:|---:|---:|
| `pedidos-dominio` | 85,4 % | 67,3 % | 83,1 % |
| `pedidos-api` | 91,6 % | 70,6 % | 91,2 % |
| `pedidos-cliente` | 97,6 % | 50,0 % | 97,8 % |
| `pedidos-gateway` | 93,9 % | 83,3 % | 96,7 % |

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
(`docker-compose.ci.yml` y `scripts/verificar-ci.sh`).

| Qué | Estado |
|---|---|
| Las 10 etapas del `Jenkinsfile` | **verificado**: ejecutadas por Jenkins, en verde |
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

### Salida real del pipeline

```
[Pipeline] { (Declarative: Checkout SCM)
Cloning repository https://github.com/adriangainzahuep2/bdo-pedidos.git
Checking out Revision ... (refs/remotes/origin/main)
[Pipeline] { (Build)                     BUILD SUCCESSFUL
[Pipeline] { (Esquema PostgreSQL)        PostgreSQL: PostgreSQL 16.x  (initdb/pg_ctl/psql)
[Pipeline] { (Tests unitarios e integracion)   135 tests, 0 fallos
[Pipeline] { (Cobertura (JaCoCo))        informes JaCoCo de los seis modulos
[Pipeline] { (Analisis de calidad (SonarQube))
+ ./gradlew --console=plain sonar
Injecting SonarQube environment variables using the configuration: SonarQube
BUILD SUCCESSFUL
[Pipeline] { (Quality gate)
SonarQube task '...' status is 'SUCCESS'
SonarQube task '...' completed. Quality gate is 'OK'
[Pipeline] { (Empaquetado)               JARs de los ocho modulos
[Pipeline] { (Imagenes Docker)            bdo/pedidos-<modulo>:1.0.0-<build>
[Pipeline] { (Verificacion de extremo a extremo)   VERIFICACION DE EXTREMO A EXTREMO CORRECTA
Finished: SUCCESS
```

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

