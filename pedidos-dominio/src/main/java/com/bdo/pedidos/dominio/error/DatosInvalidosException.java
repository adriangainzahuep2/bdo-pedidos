package com.bdo.pedidos.dominio.error;

/**
 * Error de negocio causado por datos invalidos detectados en el dominio.
 * Se traduce a HTTP 400 (entrada invalida) o 422 (regla de negocio incumplida).
 */
public class DatosInvalidosException extends RuntimeException {

    public DatosInvalidosException(String mensaje) {
        super(mensaje);
    }

    public DatosInvalidosException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
