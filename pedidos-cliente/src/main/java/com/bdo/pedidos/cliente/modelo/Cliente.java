package com.bdo.pedidos.cliente.modelo;

import java.time.Instant;

/**
 * Cliente del dominio. Entidad simple (id, nombre, email) mas la fecha de creacion para
 * auditoria. El email es unico: es la identidad natural del cliente.
 */
public record Cliente(long id, String nombre, String email, Instant fechaCreacion) {
}
