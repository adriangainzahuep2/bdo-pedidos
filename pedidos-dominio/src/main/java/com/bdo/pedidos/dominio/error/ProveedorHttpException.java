package com.bdo.pedidos.dominio.error;

/**
 * El proveedor logistico ha respondido con un codigo de error HTTP.
 * Se conserva el codigo, el cuerpo truncado y el numero de intentos realizados
 * porque son la unica evidencia util para diagnosticar el fallo.
 */
public class ProveedorHttpException extends ErrorDominio {

    private final int status;
    private final Integer retryAfterSegundos;
    private final String cuerpo;
    private final int intentos;

    public ProveedorHttpException(int status, Integer retryAfterSegundos, String cuerpo, int intentos, String mensaje) {
        super(mensaje);
        this.status = status;
        this.retryAfterSegundos = retryAfterSegundos;
        this.cuerpo = cuerpo;
        this.intentos = intentos;
    }

    public int status() {
        return status;
    }

    public Integer retryAfterSegundos() {
        return retryAfterSegundos;
    }

    public String cuerpo() {
        return cuerpo;
    }

    public int intentos() {
        return intentos;
    }
}
