package com.bdo.pedidos.dominio.error;

/**
 * Se ha intentado una transicion de estado no permitida por la maquina de estados.
 * -&gt; HTTP 409 Conflict.
 */
public class TransicionDeEstadoInvalidaException extends ErrorDominio {

    public TransicionDeEstadoInvalidaException(String mensaje) {
        super(mensaje);
    }
}
