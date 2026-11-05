package com.bdo.pedidos.dominio.error;

/** Se ha agotado el tiempo disponible para la llamada al proveedor. -&gt; HTTP 504. */
public class ProveedorTimeoutException extends ErrorDominio {

    private final long timeoutMs;
    private final int intentos;

    public ProveedorTimeoutException(String mensaje, long timeoutMs, int intentos) {
        super(mensaje);
        this.timeoutMs = timeoutMs;
        this.intentos = intentos;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public int intentos() {
        return intentos;
    }
}
