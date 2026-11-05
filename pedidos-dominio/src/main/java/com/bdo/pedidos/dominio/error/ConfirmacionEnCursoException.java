package com.bdo.pedidos.dominio.error;

/**
 * Ya hay una confirmacion en curso para ese pedido. -&gt; HTTP 409 Conflict.
 *
 * <p>Es la respuesta que recibe la segunda de dos confirmaciones simultaneas: el cliente
 * puede reintentar mas tarde (el pedido vuelve a PENDIENTE en cuanto la primera
 * confirmacion termina).
 */
public class ConfirmacionEnCursoException extends ErrorDominio {

    public ConfirmacionEnCursoException(long pedidoId) {
        super("Ya hay una confirmacion en curso para el pedido " + pedidoId);
    }
}
