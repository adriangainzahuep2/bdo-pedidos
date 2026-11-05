package com.bdo.pedidos.cliente.web.error;

/** Ya existe un cliente con ese email. -&gt; HTTP 409. */
public class EmailDuplicadoException extends RuntimeException {

    public EmailDuplicadoException(String mensaje) {
        super(mensaje);
    }
}
