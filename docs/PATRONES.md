# Patrones de diseño aplicados

Qué patrones usa el proyecto, dónde están y por qué. Complementa a
[ARQUITECTURA.md](ARQUITECTURA.md) (cómo encaja todo) y a [DECISIONES.md](DECISIONES.md) (por qué
cada decisión y qué se descartó).

Si hay que defender solo dos, son los que responden directamente al enunciado: el
**compare-and-set con lease** (concurrencia) y la cadena **timeout → reintento → circuito**
(resiliencia).

---

## 1. Arquitectura

| Patrón | Dónde | Para qué |
|---|---|---|
| **Hexagonal (puertos y adaptadores)** | Puertos en `pedidos-dominio/.../puerto`; adaptadores en `pedidos-api` | El dominio no conoce HTTP ni SQL: se cambia WebClient por RestTemplate, o H2 por PostgreSQL, sin tocar la lógica. |
| **Microservicios con base de datos por servicio** | `pedidos-api` y `pedidos-cliente`, cada uno con la suya | Cada servicio es dueño de sus datos; pedidos consulta clientes por HTTP. |
| **API Gateway** | `pedidos-gateway` | Punto de entrada único: JWT, límite de peticiones y correlación en un solo sitio. |
| **Descubrimiento de servicios en el cliente** | Eureka + Spring Cloud LoadBalancer | Los servicios se encuentran por nombre, no por dirección fija. |
| **Propagación del token (token relay)** | `FiltroPropagacionToken` | El servicio de clientes aplica sus permisos sobre la identidad del usuario final. |
| **Identificador de correlación** | `X-Correlation-Id` en el gateway y en la API | Seguir una petición a través de todos los servicios. |
| **Configuración externalizada** | `pedidos-config` | Existe y cifra con JCE, pero los servicios aún no lo importan. |

## 2. Dominio (DDD)

| Patrón | Dónde | Para qué |
|---|---|---|
| **Raíz de agregado** | `Pedido` con sus `LineaPedido` | Las invariantes del pedido se garantizan en un único sitio. |
| **Objetos de valor** | `Dinero`, `Tarifa`, `SolicitudTarifa`, `LineaPedido` | Inmutables, validados al construirse y comparados por valor. |
| **Servicio de dominio** | `CalculadoraTotalPedido` | Cálculos puros del total, probados sin Spring. |
| **Máquina de estados** | `EstadoPedido`, con su tabla de transiciones | Rechaza las transiciones ilegales. Es una tabla, no el patrón State de GoF con una clase por estado. |
| **Objetos inmutables** | Cada transición de `Pedido` devuelve un pedido nuevo | No existe un estado a medio modificar. |
| **Instantánea (snapshot)** | Nombre y email del cliente copiados en el pedido | El pedido histórico no cambia si cambia el cliente. |
| **Errores de dominio tipados** | Jerarquía de `ErrorDominio` | Cada error se traduce de forma explícita a un código HTTP. |

## 3. Resiliencia y concurrencia

| Patrón | Dónde | Para qué |
|---|---|---|
| **Timeout + presupuesto global** | 6 s por intento y 8 s en total | El proveedor puede tardar 5 s; el cliente nunca espera más de 8 s. |
| **Reintento con backoff exponencial y jitter** | Solo ante 503, 429 y errores de conexión | Cubre los 503 intermitentes sin reintentar timeouts y sin que todas las instancias reintenten a la vez. |
| **Circuit breaker** | Envuelve a los reintentos; un 4xx no cuenta | Deja de llamar a un proveedor caído. |
| **Bulkhead y rate limiter** | 32 llamadas concurrentes y 50 por segundo | Protegen al proveedor y a nuestro servicio; rechazan sin encolar. |
| **Fallo rápido (fail fast)** | Validación del token y de los timeouts al arrancar | Un error de configuración se ve al arrancar, no con un 500 en la primera petición. |
| **Bloqueo optimista con compare-and-set** | `UPDATE ... WHERE estado = ? AND version = ?` | Dos confirmaciones simultáneas hacen una sola llamada al proveedor. |
| **Reserva con lease + barrido** | Estado `CONFIRMANDO` + `RecuperadorConfirmacionesAbandonadas` | Si el proceso muere a mitad, el pedido vuelve a `PENDIENTE`. |
| **Transacciones cortas, nunca durante la llamada externa** | Confirmación en tres fases | Un proveedor lento no retiene conexiones de la base de datos. |
| **Idempotencia** | Confirmar un pedido ya confirmado | Devuelve 200 sin volver a llamar al proveedor. |
| **Registro de auditoría** | Tabla `pedido_confirmacion_intento` | Cada intento queda registrado con su resultado, sus llamadas y su duración. |

## 4. Patrones clásicos (GoF) y otros

| Patrón | Dónde |
|---|---|
| **Builder** | `Pedido.Builder`: reconstruye el pedido desde la base de datos y en cada transición. |
| **Métodos de fábrica estáticos** | `Pedido.crear`, `Dinero.importe`, `IntentoConfirmacion.ok`, `error` y `timeout`. |
| **Strategy** | Dos implementaciones del proveedor elegidas con `app.proveedor.modo`; el predicado `CuentaParaElCircuito` y `PoliticaEsperaReintentos`. |
| **Adapter** | `ClienteServiceClient` y los clientes del proveedor traducen el contrato externo al dominio. |
| **Decorator** | Los operadores de Resilience4j que envuelven la llamada, y `ServerHttpRequestDecorator` en el gateway. |
| **Chain of Responsibility** | La cadena de `WebFilter` de WebFlux y de `GlobalFilter` del gateway. |
| **DTO y Mapper** | `CrearPedidoRequest`, `PedidoResponse`, `PedidoMapper`, `ProveedorTarifasMapper`. |
| **Objeto parámetro** | `CrearPedidoCommand`. Se parece más a un objeto parámetro que a un Command de GoF: no tiene `execute()`. |
| **Inyección de dependencias (IoC)** | Spring inyecta los puertos: el servicio no sabe qué implementación usa. |
| **Repository** | `PedidoRepository`, con SQL explícito. |
| **Arquitectura por capas** | Controller → Service → Repository. |
| **Problem Details (RFC 9457)** | `ManejadorExcepcionesGlobales`: todos los errores tienen la misma forma. |

## 5. Tests

| Patrón | Dónde |
|---|---|
| **Dobles de test (mocks)** | Mockito en `PedidoServiceTest`. |
| **Stubs de servicios externos** | WireMock para el proveedor logístico y para el servicio de clientes. |
| **Token real en los tests** | `TokenDePrueba` genera un JWT de verdad: se prueba la cadena de seguridad completa. |
