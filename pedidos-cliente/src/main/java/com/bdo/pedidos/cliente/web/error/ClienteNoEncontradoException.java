package com.bdo.pedidos.cliente.web.error;

/** El cliente solicitado no existe. -&gt; HTTP 404. */
public class ClienteNoEncontradoException extends RuntimeException {

    public ClienteNoEncontradoException(long id) {
        super("No existe el cliente con id " + id);
    }
}
