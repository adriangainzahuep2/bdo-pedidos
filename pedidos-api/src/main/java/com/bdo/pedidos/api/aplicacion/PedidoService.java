package com.bdo.pedidos.api.aplicacion;

import com.bdo.pedidos.dominio.error.ClienteNoEncontradoException;
import com.bdo.pedidos.dominio.error.ConfirmacionEnCursoException;
import com.bdo.pedidos.dominio.error.PedidoNoEncontradoException;
import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException;
import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.error.ProveedorTimeoutException;
import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.IntentoConfirmacion;
import com.bdo.pedidos.dominio.modelo.Pedido;
import com.bdo.pedidos.dominio.modelo.ResultadoTarifa;
import com.bdo.pedidos.dominio.modelo.SolicitudTarifa;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import com.bdo.pedidos.dominio.puerto.PedidoRepositoryPort;
import com.bdo.pedidos.dominio.puerto.ProveedorTarifasPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Servicio de aplicacion de pedidos. Orquesta el caso de uso y decide <b>cuando</b> se
 * toca la base de datos y <b>cuando</b> se llama al proveedor externo.
 *
 * <h2>Reglas de concurrencia y de consistencia</h2>
 * <ol>
 *   <li>La confirmacion se resuelve con un <b>compare-and-set en base de datos</b>:
 *       {@code UPDATE ... WHERE estado = 'PENDIENTE' AND version = ?}. Solo la peticion que
 *       consigue actualizar la fila es la "duena" del pedido; la otra recibe 409 y
 *       <b>no llama al proveedor</b>. No hay carrera porque la operacion es atomica en el
 *       motor de base de datos, no un "leer y luego escribir".</li>
 *   <li><b>No se mantiene ninguna transaccion abierta durante la llamada al proveedor.</b>
 *       Un proveedor que tarda 5 s no puede retener una conexion del pool ni un snapshot
 *       de datos. El flujo son tres fases cortas: (1) tomar el pedido con un UPDATE
 *       condicional, (2) llamar al proveedor sin transaccion, (3) persistir el resultado
 *       con otro UPDATE condicional.</li>
 *   <li>Si el proveedor falla, el pedido <b>vuelve a PENDIENTE</b> (queda reintentable, no
 *       bloqueado en un estado terminal) y se deja traza del intento en
 *       {@code pedido_confirmacion_intento}.</li>
 *   <li>Si el proceso muere entre las fases (1) y (3), el pedido se queda en CONFIRMANDO.
 *       Una tarea programada lo devuelve a PENDIENTE cuando el "lease" expira.</li>
 *   <li>Una vez tomado el pedido, la llamada al proveedor y la persistencia del resultado
 *       <b>no se cancelan</b> aunque el cliente HTTP se desconecte: terminan y dejan el
 *       pedido CONFIRMADO o de nuevo PENDIENTE, en lugar de dejarlo en CONFIRMANDO hasta
 *       que expire el lease.</li>
 *   <li>Confirmar un pedido ya confirmado es idempotente: devuelve el pedido tal cual,
 *       sin volver a llamar al proveedor.</li>
 * </ol>
 */
@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepositoryPort pedidos;
    private final ClientePort clientes;
    private final ProveedorTarifasPort proveedor;
    private final TransactionalOperator tx;
    private final Clock clock;

    public PedidoService(PedidoRepositoryPort pedidos,
                         ClientePort clientes,
                         ProveedorTarifasPort proveedor,
                         TransactionalOperator tx,
                         Clock clock) {
        this.pedidos = pedidos;
        this.clientes = clientes;
        this.proveedor = proveedor;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Crea un pedido con sus lineas en una sola transaccion.
     * Los importes, el peso y el numero de bultos los calcula el servidor.
     */
    public Mono<Pedido> crear(CrearPedidoCommand orden) {
        return clientes.buscarPorId(orden.clienteId())
                .switchIfEmpty(Mono.error(() -> new ClienteNoEncontradoException(orden.clienteId())))
                .flatMap(cliente -> {
                    Pedido pedido = Pedido.crear(cliente.id(), cliente.nombre(), cliente.email(),
                            orden.lineas(), orden.codigoPostalDestino(), orden.numBultos(), clock.instant());
                    return enTransaccion(pedidos.insertar(pedido));
                })
                .doOnSuccess(pedido -> log.info("Pedido {} creado para el cliente {} con importe {}",
                        pedido.id(), pedido.clienteId(), pedido.importeProductos()));
    }

    /** @return el pedido, o {@link Mono#empty()} si no existe. */
    public Mono<Pedido> obtener(long id) {
        return pedidos.buscarPorId(id);
    }

    /**
     * Confirma un pedido: obtiene el coste de envio del proveedor, lo guarda y actualiza el total.
     */
    public Mono<Pedido> confirmar(long id) {
        return pedidos.buscarPorId(id)
                .switchIfEmpty(Mono.error(() -> new PedidoNoEncontradoException(id)))
                .flatMap(this::confirmarPedido);
    }

    /** Cancela un pedido que todavia no se ha confirmado. */
    public Mono<Pedido> cancelar(long id) {
        return pedidos.buscarPorId(id)
                .switchIfEmpty(Mono.error(() -> new PedidoNoEncontradoException(id)))
                .flatMap(actual -> {
                    Pedido cancelado = actual.cancelar(clock.instant());
                    return pedidos.actualizarSiEstadoEs(id, actual.version(), EstadoPedido.PENDIENTE, cancelado)
                            .flatMap(actualizado -> actualizado
                                    ? Mono.just(cancelado)
                                    : Mono.error(new TransicionDeEstadoInvalidaException(
                                    "El pedido " + id + " ya no esta en PENDIENTE y no se puede cancelar")));
                });
    }

    private Mono<Pedido> confirmarPedido(Pedido actual) {
        if (actual.estado() == EstadoPedido.CONFIRMADO) {
            // Idempotencia: confirmar dos veces no vuelve a llamar al proveedor.
            log.info("El pedido {} ya estaba confirmado; se devuelve sin llamar al proveedor", actual.id());
            return Mono.just(actual);
        }
        if (actual.estado() == EstadoPedido.CONFIRMANDO) {
            return Mono.error(new ConfirmacionEnCursoException(actual.id()));
        }
        if (actual.estado() != EstadoPedido.PENDIENTE) {
            return Mono.error(new TransicionDeEstadoInvalidaException(
                    "El pedido " + actual.id() + " esta en " + actual.estado() + " y no se puede confirmar"));
        }

        Pedido confirmando = actual.marcarConfirmando(clock.instant());
        return pedidos.actualizarSiEstadoEs(actual.id(), actual.version(), EstadoPedido.PENDIENTE, confirmando)
                .flatMap(adquirido -> {
                    if (adquirido) {
                        return sinCancelacion(llamarProveedorYResolver(confirmando));
                    }
                    return resolverConflicto(actual.id());
                });
    }

    /**
     * Otra peticion ha ganado la carrera. Se relee el pedido para responder con precision:
     * si ya esta confirmado se devuelve el resultado correcto; si esta en curso, 409.
     */
    private Mono<Pedido> resolverConflicto(long id) {
        return pedidos.buscarPorId(id)
                .flatMap(actual -> {
                    if (actual.estado() == EstadoPedido.CONFIRMADO) {
                        return Mono.just(actual);
                    }
                    if (actual.estado() == EstadoPedido.CONFIRMANDO) {
                        return Mono.error(new ConfirmacionEnCursoException(id));
                    }
                    return Mono.error(new TransicionDeEstadoInvalidaException(
                            "El pedido " + id + " ha cambiado de estado a " + actual.estado()
                                    + " durante la confirmacion"));
                })
                .switchIfEmpty(Mono.error(new PedidoNoEncontradoException(id)));
    }

    /**
     * Fase 2: llamada al proveedor (sin transaccion abierta) y fase 3: persistencia.
     */
    private Mono<Pedido> llamarProveedorYResolver(Pedido pedido) {
        return Mono.defer(() -> {
            long inicio = System.nanoTime();
            SolicitudTarifa solicitud = new SolicitudTarifa(
                    pedido.codigoPostalDestino(), pedido.pesoTotalKg(), pedido.numBultos());

            return proveedor.calcularTarifa(solicitud)
                    .switchIfEmpty(Mono.error(() -> new ProveedorRespuestaInvalidaException(
                            "El proveedor logistico no ha devuelto ninguna tarifa")))
                    .flatMap(resultado -> confirmarConTarifa(pedido, resultado))
                    .onErrorResume(error -> revertirYPropagar(pedido, error, (System.nanoTime() - inicio) / 1_000_000L));
        });
    }

    /**
     * Ejecuta la operacion hasta el final aunque el suscriptor cancele (por ejemplo, el
     * cliente HTTP cierra la conexion a mitad de la llamada al proveedor). Sin esto, la
     * cancelacion cortaria el flujo despues de tomar el pedido y antes de persistir el
     * resultado, y el pedido se quedaria en CONFIRMANDO hasta que expirase el lease.
     */
    private static <T> Mono<T> sinCancelacion(Mono<T> operacion) {
        return Mono.fromFuture(operacion::toFuture, true);
    }

    private Mono<Pedido> confirmarConTarifa(Pedido pedido, ResultadoTarifa resultado) {
        return enTransaccion(pedidos.buscarPorId(pedido.id())
                .flatMap(actual -> {
                    if (actual.estado() != EstadoPedido.CONFIRMANDO) {
                        return Mono.error(new TransicionDeEstadoInvalidaException(
                                "El pedido " + pedido.id() + " no esta en CONFIRMANDO"));
                    }
                    Pedido confirmado = actual.confirmar(
                            resultado.tarifa().coste(), resultado.tarifa().plazoDias(), clock.instant());
                    return pedidos.actualizarSiEstadoEs(actual.id(), actual.version(), EstadoPedido.CONFIRMANDO, confirmado)
                            .flatMap(actualizado -> {
                                if (!actualizado) {
                                    return Mono.error(new TransicionDeEstadoInvalidaException(
                                            "El pedido " + pedido.id() + " ha cambiado de estado durante la confirmacion"));
                                }
                                return pedidos.registrarIntento(IntentoConfirmacion.ok(
                                                pedido.id(), resultado.tarifa().coste(), resultado.intentosProveedor(),
                                                resultado.duracionMs(), clock.instant()))
                                        .thenReturn(confirmado);
                                });
                }));
    }

    /**
     * Ante un fallo del proveedor, el pedido vuelve a PENDIENTE (reintentable) y se deja
     * traza del intento. El error original se propaga para que la capa web lo traduzca.
     */
    private Mono<Pedido> revertirYPropagar(Pedido pedido, Throwable error, long duracionMs) {
        IntentoConfirmacion intento = intentoDeError(pedido.id(), error, duracionMs);
        log.warn("Fallo al confirmar el pedido {}: {} (se devuelve a PENDIENTE)", pedido.id(), error.getMessage());

        return enTransaccion(pedidos.buscarPorId(pedido.id())
                        .flatMap(actual -> {
                            if (actual.estado() != EstadoPedido.CONFIRMANDO) {
                                return Mono.<Void>empty();
                            }
                            Pedido revertido = actual.devolverAPendiente(resumenError(error));
                            return pedidos.actualizarSiEstadoEs(actual.id(), actual.version(),
                                            EstadoPedido.CONFIRMANDO, revertido)
                                    .flatMap(actualizado -> actualizado
                                            ? pedidos.registrarIntento(intento)
                                            : Mono.<Void>empty());
                        }))
                .then(Mono.<Pedido>error(error));
    }

    /**
     * Ejecuta una operacion dentro de una transaccion reactiva.
     *
     * <p>La transaccion se abre al suscribirse y se cierra al completarse el {@link Mono},
     * de modo que el tiempo con la transaccion abierta es exactamente el de la operacion
     * (nunca el de una llamada externa lenta).
     */
    private <T> Mono<T> enTransaccion(Mono<T> operacion) {
        return tx.transactional(operacion);
    }

    private IntentoConfirmacion intentoDeError(long pedidoId, Throwable error, long duracionMs) {
        Instant ahora = clock.instant();
        if (error instanceof ProveedorTimeoutException timeout) {
            return IntentoConfirmacion.timeout(pedidoId, resumenError(error), timeout.intentos(), duracionMs, ahora);
        }
        if (error instanceof ProveedorHttpException http) {
            return IntentoConfirmacion.error(pedidoId, "HTTP_" + http.status(), resumenError(error),
                    http.intentos(), duracionMs, ahora);
        }
        if (error instanceof ProveedorNoDisponibleException noDisponible) {
            return IntentoConfirmacion.error(pedidoId, noDisponible.causa(), resumenError(error),
                    noDisponible.intentos(), duracionMs, ahora);
        }
        return IntentoConfirmacion.error(pedidoId, error.getClass().getSimpleName(), resumenError(error),
                1, duracionMs, ahora);
    }

    /** Resumen corto y legible del error, para {@code ultimo_error} y para la auditoria. */
    private String resumenError(Throwable error) {
        String mensaje = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return mensaje.length() > 480 ? mensaje.substring(0, 480) : mensaje;
    }
}
