package com.bdo.pedidos.dominio.error;

/** El pedido solicitado no existe. -&gt; HTTP 404. */
public class PedidoNoEncontradoException extends ErrorDominio {

    public PedidoNoEncontradoException(long id) {
        super("No existe el pedido con id " + id);
    }
}
