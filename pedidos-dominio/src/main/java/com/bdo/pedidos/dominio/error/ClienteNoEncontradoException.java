package com.bdo.pedidos.dominio.error;

/**
 * El cliente referenciado no existe en el servicio de clientes.
 * -&gt; HTTP 422 Unprocessable Entity (el pedido es valido, pero no se puede crear).
 */
public class ClienteNoEncontradoException extends ErrorDominio {

    private final long clienteId;

    public ClienteNoEncontradoException(long clienteId) {
        super("No existe el cliente con id " + clienteId);
        this.clienteId = clienteId;
    }

    public long clienteId() {
        return clienteId;
    }
}
