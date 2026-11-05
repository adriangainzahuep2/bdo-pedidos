package com.bdo.pedidos.dominio.puerto;

import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.IntentoConfirmacion;
import com.bdo.pedidos.dominio.modelo.Pedido;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Puerto de salida hacia el almacenamiento del pedido.
 *
 * <p>El metodo clave es {@link #actualizarSiEstadoEs}: es la <b>operacion atomica</b> que
 * garantiza que dos confirmaciones simultaneas del mismo pedido no provocan mas de una
 * llamada al proveedor. No es un "check-then-act" (leer el estado y luego escribir, que
 * tiene una ventana de carrera), sino un UPDATE condicional en base de datos:
 *
 * <pre>
 *   UPDATE pedido SET ... , version = version + 1
 *    WHERE id = ? AND estado = ? AND version = ?
 * </pre>
 *
 * Solo una de las dos peticiones concurrentes consigue actualizar la fila; la otra recibe
 * {@code false} y la aplicacion responde 409 Conflict sin llamar al proveedor.
 */
public interface PedidoRepositoryPort {

    /**
     * Inserta el pedido y sus lineas. La implementacion debe hacerlo dentro de una
     * transaccion (ver {@code PedidoService.crear}).
     */
    Mono<Pedido> insertar(Pedido pedido);

    /** @return el pedido con sus lineas, o {@link Mono#empty()} si no existe. */
    Mono<Pedido> buscarPorId(long id);

    /**
     * UPDATE condicional: escribe el nuevo estado del pedido <b>solo</b> si en la base de
     * datos el pedido sigue en {@code estadoEsperado} y con la misma {@code versionEsperada}.
     *
     * @return {@code true} si se ha actualizado exactamente una fila
     */
    Mono<Boolean> actualizarSiEstadoEs(long id, long versionEsperada, EstadoPedido estadoEsperado, Pedido pedido);

    /** Registra la traza de un intento de confirmacion (exito o fallo) para auditoria. */
    Mono<Void> registrarIntento(IntentoConfirmacion intento);

    /**
     * Recuperacion de pedidos "atascados" en {@link EstadoPedido#CONFIRMANDO} (por ejemplo,
     * si el proceso muere a mitad de la llamada al proveedor). Los devuelve a PENDIENTE.
     *
     * @return numero de pedidos recuperados
     */
    Mono<Integer> recuperarConfirmacionesAbandonadas(Instant limite);
}
