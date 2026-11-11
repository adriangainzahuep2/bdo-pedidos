package com.bdo.pedidos.cliente.modelo;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Pedidos confirmados de un cliente, segun la proyeccion que alimentan los eventos.
 *
 * <p>Es <b>consistente en diferido</b>: refleja los eventos ya recibidos, que llegan unos
 * milisegundos despues de cada confirmacion (o mas tarde, si el broker estuvo caido).
 *
 * @param importeConfirmado total confirmado por moneda (los importes nunca se suman entre monedas)
 */
public record ResumenPedidos(long clienteId, long pedidosConfirmados, Map<String, BigDecimal> importeConfirmado) {
}
