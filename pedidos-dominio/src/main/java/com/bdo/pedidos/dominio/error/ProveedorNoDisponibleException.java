package com.bdo.pedidos.dominio.error;

/**
 * El proveedor no esta disponible para atender la peticion: circuito abierto, limite de
 * peticiones saturado o grupo de hilos agotado. -&gt; HTTP 503 (reintentable).
 */
public class ProveedorNoDisponibleException extends ErrorDominio {

    private final String causa;
    private final int intentos;

    public ProveedorNoDisponibleException(String causa, String mensaje, int intentos) {
        super(mensaje);
        this.causa = causa;
        this.intentos = intentos;
    }

    public String causa() {
        return causa;
    }

    public int intentos() {
        return intentos;
    }
}
