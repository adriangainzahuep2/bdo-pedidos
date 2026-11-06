# Evidencia de verificación

Salida real de las verificaciones, ejecutadas con **JDK 21** como JVM de ejecución (el código
compila con `--release 17`, que es la versión que exige el enunciado) y PostgreSQL 16.
Todo es reproducible con los comandos de cada apartado.

---

## 1. Tests unitarios y de integración

```bash
./gradlew clean build
```

```
BUILD SUCCESSFUL
134 tests, 0 fallos, 0 errores, 0 omitidos
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
| `pedidos-auth` | AuthorizationServerIntegrationTest - authorization_code + PKCE del cliente publico | 8 | 0 | 0 |
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

## 4. Trazabilidad con el enunciado

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

