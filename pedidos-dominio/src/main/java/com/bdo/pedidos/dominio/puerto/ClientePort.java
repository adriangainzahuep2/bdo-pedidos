package com.bdo.pedidos.dominio.puerto;

import com.bdo.pedidos.dominio.modelo.Cliente;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida hacia el servicio de clientes (otro microservicio).
 * Se consulta para validar el cliente al crear un pedido y para tomar la instantanea
 * (nombre y email) que quedara congelada en el pedido.
 */
public interface ClientePort {

    /** @return el cliente, o {@link Mono#empty()} si no existe. */
    Mono<Cliente> buscarPorId(long id);
}
