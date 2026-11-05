# Decisiones de diseño — paso a paso

Documento de acompañamiento del código. **Cada apartado corresponde a una pregunta u obligación
del enunciado** y responde: *qué se ha hecho, por qué, qué alternativas se descartaron y cómo se
verifica*.

Índice:

1. [Modelo de datos y estados](#1-modelo-de-datos-y-estados)
2. [Creación del pedido (`POST /api/pedidos`)](#2-creación-del-pedido-post-apipedidos)
3. [Confirmación del pedido (`POST /api/pedidos/{id}/confirmacion`)](#3-confirmación-del-pedido-post-apipedidosidconfirmacion)
4. [El proveedor externo: lentitud, 503 y el token](#4-el-proveedor-externo-lentitud-503-y-el-token)
5. [Resiliencia: tiempos de espera, reintentos y estado del pedido](#5-resiliencia-tiempos-de-espera-reintentos-y-estado-del-pedido)
6. [Concurrencia: dos confirmaciones simultáneas](#6-concurrencia-dos-confirmaciones-simultáneas)
7. [Precisión decimal](#7-precisión-decimal)
8. [Estrategia de tests](#8-estrategia-de-tests)
9. [Arquitectura: por qué microservicios, por qué reactivo](#9-arquitectura-por-qué-microservicios-por-qué-reactivo)
10. [Seguridad](#10-seguridad)
11. [Operación: configuración, observabilidad y CI/CD](#11-operación-configuración-observabilidad-y-cicd)
12. [Resumen de alternativas descartadas](#12-resumen-de-alternativas-descartadas)

---

## 1. Modelo de datos y estados

### 1.1 Entidades

| Entidad | Campos | Nota de diseño |
|---|---|---|
| **Cliente** | `id`, `nombre`, `email` | Vive en el microservicio de clientes (dueño del dato). `email` único: es la identidad natural. |
| **Pedido** | `id`, `cliente_id`, `cliente_nombre`, `cliente_email`, `estado`, `fecha_creacion`, `fecha_confirmacion`, `fecha_inicio_confirmacion`, `importe_productos`, `coste_envio`, `importe_total`, `peso_total_kg`, `num_bultos`, `codigo_postal_destino`, `plazo_envio_dias`, `moneda`, `version`, `num_intentos_confirmacion`, `ultimo_error` | Agregado raíz. |
| **Línea de pedido** | `id`, `pedido_id`, `producto`, `cantidad`, `precio_unitario`, `peso_unitario_kg`, `importe_linea` | El importe de la línea también lo calcula el servidor. |
| **Intento de confirmación** | `pedido_id`, `resultado` (`OK`/`ERROR`/`TIMEOUT`), `codigo_error`, `mensaje`, `coste_envio`, `intentos_proveedor`, `duracion_ms`, `fecha` | Auditoría: qué se intentó, con qué resultado y a qué coste. |

**Dos extensiones al modelo del enunciado**, y por qué son necesarias:

* **`codigo_postal_destino` en el pedido.** El contrato del proveedor exige
  `codigoPostalDestino`, y el modelo del enunciado (Cliente con id/nombre/email) no tiene
  dirección. Alternativas descartadas: (a) añadir dirección al Cliente → obligaría a modelar
  direcciones y a elegir una por pedido; (b) deducirla de otro sitio → no hay de dónde. Se
  decide que el **envío es una propiedad del pedido**: cada pedido se envía a un destino.
* **`peso_unitario_kg` en la línea.** El proveedor exige `pesoKg` y `numBultos`, y el modelo del
  enunciado solo tiene producto, cantidad y precio. El peso es un atributo físico del producto
  (lo declara el cliente, como el precio) y **el servidor lo agrega**: `peso_total = Σ cantidad ×
  peso_unitario`. El número de bultos se deriva de las unidades (`Σ cantidad`) y puede ser
  sobrescrito por el cliente si empaqueta en cajas (campo opcional `numBultos`).

**Instantánea del cliente en el pedido** (`cliente_nombre`, `cliente_email`): el pedido guarda una
copia del nombre y del email en el momento de crearlo. Motivo: si el cliente cambia de nombre o de
email, el pedido histórico debe seguir reflejando con quién se contrató (los pedidos son
documentos fiscales). Además permite leer un pedido sin depender del servicio de clientes.

### 1.2 Máquina de estados

```
                 ┌──────────────────────────┐
                 │        PENDIENTE         │  ← estado inicial (creado)
                 └───┬──────────────────┬───┘
       confirmar    │                  │  cancelar
   ┌─────────────────▼──┐          ┌────▼─────────┐
   │    CONFIRMANDO      │          │  CANCELADO   │ (terminal)
   │ (estado de reserva) │          └──────────────┘
   └───┬─────────────┬───┘
       │ ok          │ fallo / timeout del proveedor
 ┌─────▼──────┐  ┌───▼────────────┐
 │ CONFIRMADO │  │  PENDIENTE     │  (reintentable)
 │ (terminal) │  └────────────────┘
 └────────────┘
```

| Estado | Significado | Transiciones salientes |
|---|---|---|
| `PENDIENTE` | Creado, sin coste de envío. Único estado en el que se puede confirmar o cancelar. | `CONFIRMANDO`, `CANCELADO` |
| `CONFIRMANDO` | Transitorio. La petición ha "tomado" el pedido y está consultando al proveedor. | `CONFIRMADO`, `PENDIENTE` (reversión por fallo) |
| `CONFIRMADO` | Tiene coste de envío, plazo e importe total. **Terminal.** | — |
| `CANCELADO` | El cliente lo canceló antes de confirmar. **Terminal.** | — |

**Por qué existe `CONFIRMANDO` y por qué vuelve a `PENDIENTE` en lugar de tener un estado
`ERROR_ENVIO`:**

* `CONFIRMANDO` es lo que hace posible la exclusión mutua. Sin él, "tomar el pedido" y "llamar al
  proveedor" serían dos operaciones separadas y la carrera volvería a existir.
* Un estado de error *terminal* convertiría un fallo externo (el proveedor está caído) en un
  estado del pedido: el cliente tendría que "reabrir" el pedido a mano. Devolverlo a `PENDIENTE`
  lo mantiene **reintentable** y el motivo queda registrado en `ultimo_error` y en la tabla de
  intentos. Es la diferencia entre un sistema que se recupera solo y uno que necesita un
  operador.

**Por qué `CANCELADO`:** el enunciado deja los estados a mi criterio. Un pedido pendiente que el
cliente ya no quiere debería poder cerrarse; si no, quedaría pendiente de confirmar para siempre.
Está implementado y probado (`PedidoControllerTest.cancelar`), pero **no es obligatorio**: el
enunciado no lo pide y no añade riesgo al flujo principal.

**La máquina de estados vive en el dominio** (`EstadoPedido`), no en la base de datos ni en los
controladores. La base de datos la refuerza con un `CHECK` (`estado IN (...)`) y con el
compare-and-set de las transiciones (§6). Hay tres capas de defensa, y la única que decide es la
primera.

---

## 2. Creación del pedido (`POST /api/pedidos`)

### 2.1 Requisitos que impone el enunciado

1. Crea el pedido **con sus líneas en una sola transacción**.
2. El importe **se calcula en el servidor**; no se acepta el que envíe el cliente.

### 2.2 Cómo se cumple

**El importe no existe en la API de entrada.** `CrearPedidoRequest` no tiene ningún campo de
importe: no hay `importeTotal`, ni `importeProductos`, ni `costeEnvio`. No es una validación
("si viene, se ignora"): el campo **no existe**. Si un cliente envía `importeTotal: 0.01`, Jackson
lo descarta (el servidor no tiene dónde ponerlo) y el total se calcula siempre a partir de las
líneas. *Verificado en* `PedidoControllerTest.ignoraElImporteDelCliente`.

**Cálculo** (`CalculadoraTotalPedido`, en el dominio, sin dependencias):

```
importe_de_la_línea = redondear(cantidad × precio_unitario, 2 decimales, HALF_UP)
importe_productos  = Σ importes_de_línea
peso_total_kg      = Σ (cantidad × peso_unitario_kg)
num_bultos         = Σ cantidad   (o el valor declarado por el cliente)
importe_total      = importe_productos + coste_envio      (solo tras confirmar)
```

**Una sola transacción**: `PedidoService.crear` ejecuta `tx.transactional(...)`; dentro se
inserta el pedido y todas sus líneas (`PedidoRepository.insertar`). Si falla una línea, se
deshace el pedido completo. *Verificado en* `PedidoRepositoryTest.insertaYLee`.

**Validación del cliente**: antes de crear el pedido, el servicio consulta al microservicio de
clientes (por HTTP, con descubrimiento, balanceo de carga y timeout propio de 2 s). Motivos:

* Un pedido debe pertenecer a un cliente existente (integridad referencial entre servicios).
* La consulta devuelve nombre y email, que se congelan en la instantánea del pedido.

Alternativa descartada: guardar `cliente_id` sin validar. Se descarta porque permite crear
pedidos de clientes inexistentes y porque la instantánea (nombre/email) saldría vacía.

Si el cliente no existe → **422** `cliente-no-encontrado`. Si el servicio de clientes no
responde → **503** (es un fallo de una dependencia, no de la petición del cliente).

### 2.3 Respuesta

`201 Created` + cabecera `Location: /api/pedidos/{id}` + el pedido creado, con `estado=PENDIENTE`,
`importeProductos` calculado y `importeTotal = null` (el total no existe hasta que hay coste de
envío).

---

## 3. Confirmación del pedido (`POST /api/pedidos/{id}/confirmacion`)

### 3.1 Flujo en tres fases

```
   ┌─ FASE 1 ─────────────────────────────────────────────────────────────┐
   │ UPDATE pedido SET estado='CONFIRMANDO', fecha_inicio_confirmacion=?, │
   │        version = version + 1                                          │
   │  WHERE id=? AND estado='PENDIENTE' AND version=?                      │
   │  → si 0 filas: otra petición ha ganado → 409 (o 200 si ya confirmado)│
   └──────────────────────────────┬───────────────────────────────────────┘
                                  │  (fuera de toda transaccion)
   ┌─ FASE 2 ───────────────────────▼───────────────────────────────────────┐
   │ POST {proveedor}/api/tarifas   (Bearer, timeout 6 s por intento,      │
   │                                 presupuesto global 8 s, hasta 3        │
   │                                 intentos ante 503/429)                │
   └──────────────────────────────┬───────────────────────────────────────┘
                                  │
   ┌─ FASE 3 ───────────────────────▼───────────────────────────────────────┐
   │ UPDATE pedido SET estado='CONFIRMADO', coste_envio=?, importe_total=?, │
   │        plazo_envio_dias=?, fecha_confirmacion=?, version=version+1    │
   │  WHERE id=? AND estado='CONFIRMANDO' AND version=?                    │
   │  + INSERT en pedido_confirmacion_intento (resultado OK)               │
   └────────────────────────────────────────────────────────────────────────┘
```

**Ninguna transacción abarca la llamada al proveedor.** Es una decisión central: una transacción
abierta durante 5 s retiene una conexión del pool de base de datos y mantiene un snapshot
(dependiendo del nivel de aislamiento) que bloquea filas. Con varias peticiones concurrentes al
proveedor lento, el pool se agotaría. Además, un proveedor lento **no** debe poder dejar el
pedido a medio confirmar: si el proceso muere, la fase 3 no se ejecuta y el pedido queda en
`CONFIRMANDO`, que la tarea de recuperación (§5.5) devuelve a `PENDIENTE`.

### 3.2 Idempotencia

Confirmar un pedido ya `CONFIRMADO` **no vuelve a llamar al proveedor**: devuelve `200` con el
pedido tal cual. Es la semántica estándar para un `POST` que el cliente puede reintentar (una
respuesta perdida en la red no debe generar un segundo cobro de envío). *Verificado en*
`PedidoControllerTest.confirmarDosVeces` y en la comprobación 5 del script de extremo a extremo.

Si el pedido está `CONFIRMANDO` (otra confirmación en curso) → **409** `confirmacion-en-curso`.
Si está `CANCELADO` → **409** `transicion-de-estado-invalida`.

---

## 4. El proveedor externo: lentitud, 503 y el token

### 4.1 El contrato, tal y como se implementa

```
POST /api/tarifas
Authorization: Bearer <token>
Content-Type: application/json
{"codigoPostalDestino": "43840", "pesoKg": 3.500, "numBultos": 2}

200 OK
{"coste": 7.95, "moneda": "EUR", "plazoDias": 2}
```

El DTO de salida (`TarifaRespuesta`) **ignora campos desconocidos** (`@JsonIgnoreProperties`): el
proveedor puede añadir atributos sin romper el cliente. La respuesta se valida: si faltan
`coste` o `plazoDias`, o si la moneda no es `EUR`, se rechaza con **502** (no se "adivina" el
importe ni se cambia la moneda en silencio).

### 4.2 Las tres condiciones que impone el enunciado

| Condición | Cómo se trata |
|---|---|
| **Puede tardar hasta 5 s** | `timeout-por-intento = 6 s` (1 s de margen sobre el peor caso) y `timeout-global = 8 s`. Un timeout **no se reintenta** (§5.3). |
| **Devuelve 503 de forma intermitente** | 503 y 429 **sí** se reintentan, con espera exponencial y jitter, hasta 3 intentos en total. |
| **Exige un token que no debe estar en el código fuente** | El token se lee de la variable de entorno `PROVEEDOR_LOGISTICO_TOKEN`. **Si falta, la aplicación no arranca**: es un fallo rápido en el arranque, no un 500 en la primera llamada. |

### 4.3 El token, en detalle

* En `application.yml` el valor es `${PROVEEDOR_LOGISTICO_TOKEN:}` (vacío por defecto) y la
  propiedad está anotada con `@NotBlank` + `@Validated` → **fallo en el arranque** si no se
  proporciona.
* El servidor de configuración (`pedidos-config`) admite valores cifrados con JCE
  (`{cipher}...`) y los descifra con la clave del entorno (`CONFIG_ENCRYPT_KEY`), que **tampoco**
  está en el repositorio; `config-repo/pedidos-api.yml` explica cómo cifrar el token. En esta
  entrega los microservicios **no** importan todavía el Config Server (ver README, "Qué queda
  fuera"): el token llega por el entorno.
* En los tests, el token es una constante de test (`token-de-prueba`), que es lo correcto: un
  token de pruebas no es un secreto.
* El token **nunca** se registra en los logs: el adaptador registra el coste, la duración y el
  número de intentos, nunca las cabeceras.

---

## 5. Resiliencia: tiempos de espera, reintentos y estado del pedido

> *Requisito técnico 1: «Decide y documenta cómo se comporta el sistema cuando el proveedor tarda
> o falla: tiempos de espera, reintentos y en qué estado queda el pedido.»*

### 5.1 Tiempos de espera

| Timeout | Valor | Justificación |
|---|---|---|
| `connect-timeout` | **1 s** | Si el proveedor no acepta la conexión, no hay nada que esperar: se falla ya. |
| `timeout-por-intento` | **6 s** | El enunciado dice que puede tardar hasta 5 s. Un límite de 5 s exactos sería una lotería (el 5,001 s sería un fallo); 6 s da 1 s de margen. |
| `timeout-global` | **8 s** | Presupuesto de toda la operación, **incluidos los reintentos y las esperas entre ellos**. Es el único timeout que ve el cliente. |

El presupuesto global (8 s) está por debajo del tiempo típico de espera de un proxy inverso o de
un balanceador de carga (30–60 s): preferimos responder 504 con información útil antes que dejar
que el cliente agote su propio tiempo de espera sin saber qué ha pasado.

### 5.2 Reintentos

| Parámetro | Valor | Justificación |
|---|---|---|
| Intentos totales | **3** (1 inicial + 2 reintentos) | Tres intentos cubren un 503 puntual sin multiplicar la carga. |
| Se reintenta | **503, 429 y errores de conexión** | Fallos rápidos y transitorios: el proveedor sigue vivo. |
| **No** se reintenta | **timeout** | Una respuesta legítima puede tardar 5 s. Reintentar un timeout gastaría el presupuesto global sin garantía de éxito: es una decisión de criterio, no un descuido. |
| Espera | **200 ms × 2ⁿ, jitter ±50 %** | El backoff exponencial evita martillear al proveedor; el **jitter** evita que todas las instancias reintenten en el mismo milisegundo (*thundering herd*). |
| `Retry-After` | **se respeta** (acotado a 60 s) | Si el proveedor indica cuándo reintentar, se hace caso (segundos o fecha HTTP). Si la espera **no cabe en el presupuesto global**, no se espera: se devuelve el error en el acto en lugar de acabar en un timeout. |
| **No** se reintenta | **4xx** (salvo 429) y **respuestas inválidas** | Son errores de nuestra petición o del contrato: repetirlos daría el mismo resultado. |

Las dos implementaciones (reactiva y bloqueante) comparten **una única clasificación de fallos**
(`ClasificadorFallosProveedor`) y la misma política de espera (`PoliticaEsperaReintentos`), que
se derivan de `app.proveedor.*`. Cada intento traduce su fallo técnico (timeout de Reactor,
timeout de respuesta de Netty, error de conexión, JSON mal formado...) a un error de dominio
**antes** de decidir si se reintenta, de modo que los reintentos y el circuito razonan sobre
errores de negocio y no sobre excepciones de la librería HTTP.

### 5.3 Orden de las capas de resiliencia (y por qué importa)

De fuera hacia dentro:

```
Bulkhead → RateLimiter → CircuitBreaker → timeout global (8 s) → [ reintentos ] → timeout por intento (6 s) → HTTP
```

* **El circuito envuelve a los reintentos.** Si fuese al revés, el circuito contaría un fallo por
  cada intento: tres 503 seguidos abrirían el circuito por una sola petición de negocio, que es
  exactamente lo contrario de lo que se quiere (el sistema sano se aislaría a sí mismo).
* **El bulkhead va fuera**: si no hay plazas, se rechaza de inmediato (fallo rápido) en lugar de
  encolar y agotar la memoria.
* **El limitador de peticiones también fuera**: las peticiones rechazadas por el límite no cuentan
  como fallos del circuito (el proveedor no ha fallado; hemos decidido no molestarle).
* **Qué abre el circuito** lo decide un predicado en código
  (`ClasificadorFallosProveedor.CuentaParaElCircuito`, enlazado desde `record-failure-predicate`
  en el YAML): 5xx, 429, timeouts, errores de conexión y respuestas inválidas. Un **4xx no
  cuenta**: el proveedor está sano y el error es nuestro; abrir el circuito dejaría sin servicio
  al resto de pedidos.

### 5.4 Qué pasa en cada escenario

| Escenario | Respuesta HTTP | Estado del pedido | Llamadas al proveedor |
|---|---|---|---|
| Proveedor responde 200 | `200` | `CONFIRMADO` (con coste, plazo y total) | 1 |
| Proveedor responde 503 y luego 200 | `200` | `CONFIRMADO` | 2 |
| Proveedor responde 503 siempre | `502` `proveedor-error` | **`PENDIENTE`** + `ultimoError` + intento registrado (`ERROR`) | 3 |
| Proveedor tarda más del timeout | `504` `proveedor-timeout` | **`PENDIENTE`** + intento registrado (`TIMEOUT`) | 1 (los timeouts no se reintenta) |
| Circuito abierto | `503` `proveedor-no-disponible` | **`PENDIENTE`** | 0 (no se llama) |
| Límite de peticiones saturado | `503` `proveedor-no-disponible` | **`PENDIENTE`** | 0 |
| Respuesta vacía, no JSON, JSON mal formado o moneda no soportada | `502` `proveedor-error` | **`PENDIENTE`** | 1 |
| Proveedor responde 4xx (p. ej. 400) | `502` `proveedor-error` (no cuenta para el circuito) | **`PENDIENTE`** | 1 (no se reintenta) |
| No se puede conectar | `503` `proveedor-no-disponible` | **`PENDIENTE`** | 3 |
| 503 con `Retry-After` mayor que el presupuesto | `502` `proveedor-error` | **`PENDIENTE`** | 1 |
| El cliente HTTP cierra la conexión a mitad | — | `CONFIRMADO` o `PENDIENTE` (la operación termina igualmente) | 1 |

*Verificado en* `PedidoControllerTest.falloDelProveedor` (502 y el pedido queda `PENDIENTE`),
`PedidoControllerTimeoutTest` (504), `PedidoControllerTest.respuestaVaciaDelProveedor` (502) y
`ProveedorTarifasClientReactivoTest` (un caso por fila de la tabla; circuito abierto: 0 llamadas).

**Cancelación.** Una vez tomado el pedido, la llamada al proveedor y la persistencia del resultado
se ejecutan **aunque el cliente HTTP se desconecte** (`PedidoService.sinCancelacion`). Sin esto, la
cancelación de la petición cortaría el flujo reactivo entre la fase 1 y la 3 y el pedido se
quedaría en `CONFIRMANDO` hasta que expirase el *lease*. *Verificado en*
`PedidoServiceTest.confirmarTerminaAunqueSeCanceleLaPeticion`.

### 5.5 Recuperación de confirmaciones abandonadas

Si el proceso muere entre las fases 1 y 3, el pedido queda en `CONFIRMANDO` y nadie podría volver
a confirmarlo. Una tarea programada (`RecuperadorConfirmacionesAbandonadas`) devuelve a `PENDIENTE`
los pedidos cuya `fecha_inicio_confirmacion` es anterior al *lease* (2 minutos, holgadamente
superior al peor caso de 8 s + persistencia). *Verificado en*
`PedidoRepositoryTest.recuperarConfirmacionesAbandonadas` y, sobre PostgreSQL, en
`db/postgresql/verificacion.sql`.

---

## 6. Concurrencia: dos confirmaciones simultáneas

> *Requisito técnico 2: «Dos confirmaciones simultáneas del mismo pedido no deben provocar dos
> llamadas al proveedor ni dejar datos inconsistentes.»*

### 6.1 Por qué no las soluciones habituales

| Solución | Por qué no |
|---|---|
| `synchronized` / `ReentrantLock` en la JVM | Solo funciona dentro de una instancia. Con dos réplicas del servicio, las dos peticiones pueden entrar en instancias distintas. |
| `SELECT ... FOR UPDATE` | Bloquea la fila durante toda la llamada al proveedor (hasta 5 s): exactamente el problema que se quiere evitar. |
| Cachear el estado en memoria | Divergencia entre la caché y la base de datos. |
| **Compare-and-set en base de datos** | **Elegida**: la atomicidad la garantiza el motor de base de datos, funciona entre instancias y no bloquea más que el propio `UPDATE`. |

### 6.2 El mecanismo

```sql
UPDATE pedido
   SET estado = 'CONFIRMANDO', fecha_inicio_confirmacion = ?, version = version + 1
 WHERE id = ? AND estado = 'PENDIENTE' AND version = ?
```

* Es **una sola sentencia**: o se aplica o no. No hay ventana entre el `SELECT` y el `UPDATE`.
* La condición `estado = 'PENDIENTE'` es la exclusión mutua: solo una petición pasa de `PENDIENTE`
  a `CONFIRMANDO`.
* La condición `version = ?` es **bloqueo optimista**: si el pedido ha cambiado desde que lo
  leyó (por ejemplo, alguien lo canceló), la actualización tampoco se aplica.
* La columna `version` se incrementa en cada transición ✓.

La versión se gestiona con dos reglas, para que nunca se desincronice:

1. La transición se persiste **siempre** con un `UPDATE ... WHERE estado = ? AND version = ?`.
2. Después de una transición, el servicio **vuelve a leer** el pedido: la versión fresca es la que
   manda. Nunca se "adivina" incrementando un contador en memoria.

### 6.3 Qué hace la petición perdedora

| Situación | Respuesta |
|---|---|
| El `UPDATE` no afecta a ninguna fila **y** el pedido ya está `CONFIRMADO` | `200` con el pedido (idempotencia) |
| El `UPDATE` no afecta a ninguna fila **y** el pedido está `CONFIRMANDO` | `409 confirmacion-en-curso` (reintentable) |
| El `UPDATE` no afecta a ninguna fila **y** el pedido está en otro estado | `409 transicion-de-estado-invalida` |

En **ningún** caso la perdedora llama al proveedor ✓.

### 6.4 "Ni dejar datos inconsistentes"

* El pedido nunca tiene un `importe_total` sin `coste_envio` (lo impide el modelo: `importeTotal`
  solo se calcula en la transición a `CONFIRMADO`).
* El pedido nunca queda en `CONFIRMANDO` para siempre (lease + tarea de recuperación, §5.5).
* `coste_envio`, `plazo_envio_dias`, `importe_total` y `fecha_confirmacion` se escriben **en la
  misma sentencia** que el cambio de estado: no existe un instante en el que el pedido esté
  `CONFIRMADO` sin coste, ni tenga coste sin total.
* Cada intento (con éxito o con error) deja una fila en `pedido_confirmacion_intento`: siempre se
  puede responder "qué pasó con este pedido".

### 6.5 Verificación

* `PedidoControllerTest.dosConfirmacionesSimultaneas`: dos **hilos reales** con dos peticiones
  HTTP simultáneas → una respuesta `200`, otra `409`, y **exactamente una** llamada al proveedor
  (verificado con `WireMock.verify(exactly(1), ...)`).
* `PedidoControllerTest.diezConfirmacionesSimultaneas`: diez peticiones a la vez → una `200`,
  nueve `409`, una sola llamada al proveedor y un único intento `OK` en la auditoría.
* `PedidoRepositoryTest.transicionCondicional`: el mismo `UPDATE` con la misma versión esperada se
  aplica una vez y la segunda vez no.
* `scripts/verificar-e2e.sh`, comprobación 6: lo mismo a través del API Gateway y Eureka.

---

## 7. Precisión decimal

> *Requisito técnico 3: «Importes con precisión decimal adecuada.»*

### 7.1 Decisiones

| Decisión | Detalle |
|---|---|
| **`BigDecimal`, nunca `double`/`float`** | `0.1 + 0.2 != 0.3` en binario. Con `double`, un total de 111,48 € puede acabar en 111,47999999999998 € y, al persistirlo, en 111,48 € (o en 111,47 € si el redondeo cae en otro lado). Un céntimo de diferencia en una factura es un bug de producción. |
| **Escala explícita** | Precios unitarios con **4 decimales** (escala del catálogo: `45.9900`), importes con **2** (centimos). El `BigDecimal` conserva la escala, así que `45.9900` no se convierte en `45.99` por accidente. |
| **Redondeo `HALF_UP`** | Redondeo comercial, el habitual en facturación en España: 0,005 → 0,01. La alternativa es `HALF_EVEN` (redondeo bancario: el medio va al par, 0,005 → 0,00 y 0,015 → 0,02), que reduce el sesgo al sumar muchos importes; no se usa porque en un importe a pagar el cliente espera que la mitad se redondee hacia arriba. |
| **Redondeo por línea** | `importe_linea = redondear(cantidad × precio_unitario, 2)`, y el importe de productos es la **suma de las líneas ya redondeadas**. Si se redondease solo la suma, el total podría no coincidir con la suma de lo que el cliente ve en el detalle (el clásico "diferencia de un céntimo"). |
| **Columnas `NUMERIC`** | `NUMERIC(19,2)` para importes, `NUMERIC(19,4)` para precios, `NUMERIC(10,3)` para pesos. En PostgreSQL, `NUMERIC` es exacto; `DOUBLE PRECISION` no lo es. |
| **Invariantes también en la base de datos** | `CHECK` que exigen `importe_linea = ROUND(cantidad × precio, 2)`, `importe_total = importe_productos + coste_envio`, que un `CONFIRMADO` tenga coste, total, plazo y fecha, y que ningún otro estado tenga total. La base de datos rechaza un pedido inconsistente aunque se escriba SQL a mano. |
| **La moneda viaja siempre** | `Dinero` incluye la moneda y **rechaza** sumar importes de monedas distintas (error de programación, no un `IllegalArgumentException` genérico). Y si el proveedor devuelve una moneda que no es EUR → **502**, no se convierte en silencio. |
| **El servidor calcula, el cliente propone** | El cliente envía precio unitario y cantidad; el importe de la línea, el importe de productos, el peso total, los bultos y el total los calcula el servidor. |

### 7.2 Verificación

`CalculadoraTotalPedidoTest` (15 casos) cubre: suma de líneas, redondeo `HALF_UP` por línea
(3 × 0,3350 → 1,01 y 0,0050 → 0,01), líneas del mismo producto, precisión exacta de céntimos
(0,10 + 0,20 = 0,30), rechazo de monedas distintas y el cálculo de pesos y bultos.

---

## 8. Estrategia de tests

> *Requisito técnico 4: «Un test unitario del cálculo del total y un test de integración que
> simule un error o un timeout del proveedor.»*

### 8.1 Pirámide aplicada

```
        /\          E2E:  scripts/verificar-e2e.sh   (stack completo, OAuth2, Eureka, Gateway)
       /  \         Integración: WireMock (proveedor), H2 real (persistencia), servidor real (API)
      /----\        Unitarios: dominio puro (sin Spring) + servicio de aplicación (dobles)
     /      \
```

| Nivel | Qué prueba | Por qué ese nivel |
|---|---|---|
| **Unitario (dominio)** | `CalculadoraTotalPedidoTest`, `PedidoTest`, `EstadoPedidoTest` | Sin Spring ni base de datos: milisegundos por caso y se pueden cubrir todas las ramas (incluidas las que "no deberían pasar"). El cálculo del total que pide el enunciado vive **aquí**, y además **también** en la integración. |
| **Unitario (aplicación)** | `PedidoServiceTest` | Verifica la **orquestación**: qué se llama, en qué orden, y que el proveedor **no** se llama cuando la petición pierde la carrera. Con dobles (Mockito), sin infraestructura. |
| **Integración (persistencia)** | `PedidoRepositoryTest` | Contra **H2 real** (no un mock): verifica el compare-and-set, el mapeo de `NUMERIC` a `BigDecimal` y las timestamps. Un doble no probaría el SQL. |
| **Integración (proveedor)** | `ProveedorTarifasClientReactivoTest`, `...BloqueanteTest` | **WireMock** simula al proveedor: 200 correcto, 503, latencia, respuesta inválida, circuito abierto. Se verifica el **cuerpo y las cabeceras** que salen (`equalToJson`, `withHeader("Authorization", ...)`): el contrato se comprueba, no solo el resultado. |
| **Integración (API)** | `PedidoControllerTest` | Servidor real (puerto aleatorio) + **JWT real** (HS256 firmado con la clave de test): se ejercita la cadena de seguridad completa, la validación, la traducción de errores y la concurrencia con dos hilos. |
| **Extremo a extremo** | `scripts/verificar-e2e.sh` | Los seis servicios reales, Eureka, OAuth2 y el gateway: es la prueba que valida la **arquitectura**, no solo el código. |
| **Integración (clientes)** | `ClienteApiIntegrationTest`, `ClienteServiceClientTest` | Alta, unicidad de email (también en carrera), validación y permisos; y el adaptador que lo consume (404 → no existe, 5xx/timeout → no disponible). |
| **Integración (gateway)** | `GatewayIntegrationTest`, `GatewayLimitePeticionesTest` | Gateway real delante de WireMock: JWT antes de enrutar, correlación propagada, sin rutas automáticas, 429. |
| **Base de datos real** | `scripts/probar-postgresql.sh` | Esquema, 10 filas por tabla y verificación sobre un PostgreSQL desechable; comprueba que el DDL consolidado y el de los servicios son idénticos. |

### 8.2 Los dos tests que pide el enunciado, concretamente

* **Test unitario del cálculo del total** → `pedidos-dominio/.../CalculadoraTotalPedidoTest`:
  sin Spring, sin base de datos, 15 casos que incluyen el redondeo y la precisión de céntimos.
* **Test de integración con error del proveedor** → `ProveedorTarifasClientReactivoTest.reintentaAnte503`
  (WireMock devuelve 503 siempre → se verifica que se hacen **3** intentos y que el error se
  propaga como `ProveedorHttpException` con `intentos = 3`) y
  `...timeoutNoSeReintenta` (el proveedor tarda más del timeout → `ProveedorTimeoutException`
  y **una sola** llamada, que documenta la decisión de no reintentar timeouts).
  Y a nivel de API: `PedidoControllerTest.falloDelProveedor` (502 y el pedido queda `PENDIENTE`)
  y `...timeoutDelProveedor` (504).

### 8.3 Principios aplicados

* **Los dobles solo donde tiene sentido**: en el servicio de aplicación (para verificar la
  orquestación) y en ningún sitio donde importe el SQL real.
* **Sin sleeps**: la concurrencia se prueba con hilos reales y un proveedor que tarda 1,5 s
  (holgado para que la primera petición siga en vuelo cuando llegan las demás, incluso con la
  máquina cargada).
* **Tests que verifican lo que no debe pasar**: "la segunda confirmación **no** llama al
  proveedor", "el proveedor **no** se llama cuando el circuito está abierto", "el cliente **no**
  puede fijar el importe".
* **Presupuestos de tiempo realistas**: el timeout que se espera en el escenario de *timeout* es
  corto y explícito (1 s) y el del resto de los escenarios es holgado (5 s). Un timeout de test
  heredado del valor de producción puede hacer que el test **falle por saturación de la
  máquina** en lugar de por un defecto del código: así se descubrió un test intermitente al
  migrar el build a Gradle (que compila los ocho módulos en paralelo) y se corrigió dando a
  cada escenario el presupuesto que necesita.
* **Verificable en un comando**: `./gradlew build`, `./scripts/probar-postgresql.sh` y
  `./scripts/verificar-e2e.sh` (también con `E2E_BD=postgres`).

---

## 9. Arquitectura: por qué microservicios, por qué reactivo

### 9.1 Por qué microservicios (y no un monolito modular)

**El enunciado no pide microservicios**: pide un servicio de pedidos con una integración. Es
una decisión propia y tiene un coste claro (más piezas que operar y bastante más que las ~3 h
orientativas). Para un único equipo, un **monolito modular** con los mismos puertos y
adaptadores sería la opción por defecto, y el dominio (`pedidos-dominio`, Java puro) permite
volver a ella sin tocar la lógica. Se mantiene porque permite discutir en la revisión los
escenarios de arquitectura (descubrimiento, gateway, seguridad entre servicios) y porque, en
este dominio, las fronteras son reales:

* **Fronteras de negocio reales**: "pedidos" y "clientes" son bounded contexts distintos con sus
  propias reglas (un pedido tiene estados y un cliente no). El proveedor logístico es un
  **tercer** contexto, y encima es una dependencia externa que no controlamos.
* **Escalado independiente**: el servicio de pedidos es el que recibe el tráfico; el de clientes
  es de consulta. Y si el proveedor tarda 5 s, la latencia se absorbe en el servicio de pedidos
  sin tocar el resto.
* **Fallos independientes**: si el servicio de clientes cae, el de pedidos sigue sirviendo pedidos
  de clientes ya conocidos... (matiz: en esta versión el alta de pedido **sí** depende del
  servicio de clientes; ver §12 sobre cómo se evolucionaría).
* **El API Gateway es el punto donde se pueden añadir políticas transversales** (autenticación,
  limitación de peticiones, correlación, registro) sin tocar los servicios.

### 9.2 Por qué reactivo de extremo a extremo

El dato que manda: **el proveedor tarda hasta 5 segundos**. En una arquitectura bloqueante, cada
petición en curso mantiene un hilo del pool (Tomcat: 200 por defecto) durante esos 5 s. Con 50
peticiones concurrentes, el 25 % del pool está bloqueado esperando al proveedor. En WebFlux, el
hilo se libera mientras se espera: el mismo servidor sostiene muchos más pedidos concurrentes
con menos memoria.

Consecuencias en el diseño:

* **WebFlux** (no `spring-boot-starter-web`) en el servicio de pedidos, el de clientes y el gateway.
* **R2DBC** (no JDBC/JPA): las APIs reactivas de Spring Data solo funcionan con drivers no
  bloqueantes; usar JPA obligaría a bloquear el event loop, que es el error clásico.
* **WebClient** (no `RestTemplate`) para llamar al proveedor.
* **Puertos reactivos** (`Mono`) en el dominio: el contrato con la infraestructura es reactivo de
  principio, no "reactivo en el adaptador". Es un compromiso explícito y documentado: las
  entidades y el cálculo siguen siendo Java puro.

### 9.3 Por qué `RestTemplate` no es el adaptador por defecto (pero existe)

`RestTemplate` es bloqueante: si se usara dentro de WebFlux, bloquearía el event loop de Netty
y, con suficientes peticiones concurrentes, el servicio se quedaría sin hilos. Por eso el
adaptador por defecto es `WebClient`.

Aun así hay razones para mantener un adaptador con `RestTemplate` (el enunciado no lo pide):

1. **Comparación objetiva**: los dos adaptadores implementan el mismo puerto y se prueban con la
   misma suite contra el mismo stub. Eso demuestra que la elección es de infraestructura, no de
   negocio.
2. **Migración**: para un equipo con stack bloqueante (Spring MVC + JPA), el puerto ya está
   definido: basta cambiar el adaptador.

El adaptador bloqueante delega la llamada bloqueante en `Schedulers.boundedElastic()` (el pool
reservado a E/S bloqueante) y la protege con el mismo presupuesto de 8 s. Está activo con
`app.proveedor.modo=bloqueante`.

### 9.4 Por qué el descubrimiento de servicios y no direcciones fijas

Con `lb://pedidos-api` y Eureka, el enrutamiento sobrevive a que una instancia caiga, y añadir una
réplica no requiere tocar la configuración del gateway ni de los clientes. Es el equivalente actual
de Ribbon en el ecosistema Netflix OSS (Spring Cloud LoadBalancer).

El coste: el descubrimiento introduce un estado distribuido (el registro) que hay que operar
— por eso el script de verificación **espera al alta real en Eureka** en lugar de dormir un
tiempo fijo.

---

## 10. Seguridad

* **OAuth2 con JWT (RS256)**: el servidor de autorización (`pedidos-auth`) emite los tokens y
  publica su JWKS; gateway y microservicios los validan. Nadie comparte clave privada con el
  emisor.
* **Permisos por *scope***, no por rol: `pedidos.read`, `pedidos.write`, `clientes.read`,
  `clientes.write`. El *scope* viaja en el token y se traduce a autoridades `SCOPE_*`.
* **El gateway valida antes de enrutar**; los microservicios vuelven a validar (defensa en
  profundidad: un servicio puede alcanzarse sin pasar por el gateway).
* **Los secretos no están en el código**: token del proveedor, secretos de los clientes OAuth y
  clave de cifrado JCE llegan del entorno.
* **El token del cliente final se propaga** a la llamada entre microservicios (Contexto de
  Reactor): el servicio de clientes aplica sus propias políticas sobre la misma identidad.
* **Sin sesiones**: el estado es el token (stateless), lo que permite escalar horizontalmente sin
  affinity de sesión.
* **Errores sin fuga de información**: el manejador global devuelve un 500 genérico y registra el
  detalle interno en el log; nunca se devuelve una traza al cliente.

---

## 11. Operación: configuración, observabilidad y CI/CD

* **Configuración centralizada** (Spring Cloud Config, backend `native`) con **cifrado JCE** de
  los valores sensibles y endpoints `/encrypt` y `/decrypt`. Los servicios aún no la importan
  (README, "Qué queda fuera").
* **Correlación de extremo a extremo**: `X-Correlation-Id` generado en el gateway (si el cliente no
  lo envía), reenviado al microservicio y devuelto en la respuesta. **No** se escribe en el MDC:
  en WebFlux una petición cambia de hilo y un `ThreadLocal` se quedaría pegado a otras
  peticiones; llevarlo a los logs requiere Micrometer Context Propagation (mejora documentada).
* **Métricas**: Actuator expone `health`, `info` y `metrics`; los probes
  `liveness`/`readiness` están activados para la orquestación.
* **Descubrimiento**: la consola de Eureka en `:8761` muestra las instancias registradas.
* **CI/CD**: `Jenkinsfile` (pipeline declarativo: compilación → esquema PostgreSQL → tests →
  cobertura → Sonar → empaquetado → imágenes → E2E). La configuración de Sonar está en
  `build.gradle.kts` (bloque `sonar`): el plugin de Gradle no lee `sonar-project.properties`.
  `docker-compose.yml` levanta el entorno completo con PostgreSQL (una base de datos por servicio).

---

## 12. Resumen de alternativas descartadas

| Decisión | Alternativas descartadas | Motivo |
|---|---|---|
| Compare-and-set en base de datos | `synchronized`, bloque pesimista, caché en memoria | No funciona entre instancias / bloquea durante la llamada externa |
| Estado `CONFIRMANDO` reversible | Estado `ERROR_ENVIO` terminal | Un fallo externo no debe dejar el pedido en un callejón sin salida |
| Reintentar solo transitorios | Reintentar todo | Un timeout sobre un proveedor lento multiplica la latencia sin garantía |
| Circuito por encima de los reintentos | Circuito por debajo | Evitar que 3 × 503 de una sola petición abran el circuito |
| `BigDecimal` con 2/4 decimales | `double`, escalares | Precisión binaria; diferencias de céntimos |
| Redondeo por línea | Redondear solo el total | El total debe coincidir con la suma del detalle |
| Importes fuera de la API de entrada | Aceptarlos y "sanearlos" | Lo que no existe no se puede manipular |
| WebClient (reactivo) | RestTemplate por defecto | `RestTemplate` bloquea el event loop (se mantiene como alternativa) |
| Token propagado entre servicios | Token de servicio propio | Menos infraestructura; si las políticas difieren, token propio (documentado) |
| Repositorios con SQL explícito | `ReactiveCrudRepository.save()` | H2 no soporta `INSERT ... RETURNING`; además el CAS necesita SQL explícito |
| Secuencia de base de datos para los ids | `IDENTITY` | `nextval` es portable entre H2 y PostgreSQL y permite conocer el id antes de insertar |
| Sin idempotency key | Clave de idempotencia | No lo pide el enunciado; documentado como mejora (README §6) |

---

*Cada afirmación de este documento es verificable: los tests citados están en el repositorio y
`./scripts/verificar-e2e.sh` comprueba el comportamiento sobre la arquitectura completa.*
