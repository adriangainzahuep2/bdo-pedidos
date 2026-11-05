package com.bdo.pedidos.dominio.error;

/**
 * Raiz de los errores de dominio. Permite al adaptador web mapear de forma explicita
 * cada error de negocio a un codigo HTTP, sin acoplar el dominio a Spring.
 */
public abstract class ErrorDominio extends RuntimeException {

    protected ErrorDominio(String mensaje) {
        super(mensaje);
    }

    protected ErrorDominio(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
