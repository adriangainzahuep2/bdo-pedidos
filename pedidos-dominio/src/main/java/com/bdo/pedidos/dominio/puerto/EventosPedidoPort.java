package com.bdo.pedidos.dominio.puerto;

import com.bdo.pedidos.dominio.evento.PedidoConfirmado;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida para publicar eventos de dominio del pedido.
 *
 * <p>Contrato: la publicacion es <b>de mejor esfuerzo</b> y nunca falla hacia el caso de
 * uso. Si el broker no esta disponible, el adaptador lo registra (log y metrica) y completa:
 * el pedido ya esta confirmado en la base de datos y eso no se deshace porque no se haya
 * podido avisar. La garantia "al menos una vez" exigiria el patron outbox (el evento en la
 * misma transaccion que la confirmacion), que queda documentado como siguiente paso.
 */
public interface EventosPedidoPort {

    /** Publica que un pedido ha quedado confirmado. Completa siempre, sin error. */
    Mono<Void> publicar(PedidoConfirmado evento);
}
