package com.bdo.pedidos.cliente.eventos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lo que el servicio de clientes necesita del evento {@code PedidoConfirmado}.
 *
 * <p>Es una copia propia del contrato, no una dependencia del modulo de pedidos: cada
 * servicio se despliega por separado y el consumidor solo lee los campos que usa
 * ("tolerant reader"). {@link JsonIgnoreProperties#ignoreUnknown()} es imprescindible: el
 * conversor de Spring Cloud Function NO usa el Jackson de Spring Boot y falla con las
 * propiedades desconocidas, asi que sin la anotacion el evento real (que trae ademas
 * {@code importeProductos}, {@code costeEnvio}...) no se podria leer, y cualquier campo que
 * el productor anadiese en el futuro romperia al consumidor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PedidoConfirmadoEvento(
        long pedidoId,
        long clienteId,
        BigDecimal importeTotal,
        String moneda,
        Instant fechaConfirmacion) {
}
