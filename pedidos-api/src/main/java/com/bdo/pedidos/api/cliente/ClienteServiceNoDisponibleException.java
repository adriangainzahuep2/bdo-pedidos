package com.bdo.pedidos.api.cliente;

/**
 * El servicio de clientes no esta disponible (no responde, o responde con error).
 * -&gt; HTTP 503: no se puede crear el pedido porque falta validar el cliente.
 */
public class ClienteServiceNoDisponibleException extends RuntimeException {

    public ClienteServiceNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
