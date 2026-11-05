package com.bdo.pedidos.dominio.error;

/**
 * La respuesta del proveedor no cumple el contrato (campos ausentes, tipo numerico
 * inesperado o moneda distinta de la soportada). -&gt; HTTP 502.
 */
public class ProveedorRespuestaInvalidaException extends ErrorDominio {

    public ProveedorRespuestaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
