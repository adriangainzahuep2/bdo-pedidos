package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;

/**
 * Cliente del servicio de pedidos (datos replicados desde el bounded context de clientes).
 * Es una copia local: el servicio de pedidos no comparte base de datos con el de clientes.
 */
public record Cliente(long id, String nombre, String email) {

    public Cliente {
        if (id <= 0) {
            throw new DatosInvalidosException("El identificador de cliente debe ser positivo");
        }
        if (nombre == null || nombre.isBlank()) {
            throw new DatosInvalidosException("El nombre del cliente es obligatorio");
        }
        if (email == null || email.isBlank()) {
            throw new DatosInvalidosException("El email del cliente es obligatorio");
        }
    }
}
