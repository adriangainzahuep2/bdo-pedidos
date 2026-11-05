package com.bdo.pedidos.api.aplicacion;

import com.bdo.pedidos.dominio.modelo.LineaPedido;

import java.util.List;

/**
 * Orden de creacion de un pedido (capa de aplicacion).
 * Los importes NO forman parte de la orden: los calcula el servidor.
 */
public record CrearPedidoCommand(long clienteId,
                                  String codigoPostalDestino,
                                  Integer numBultos,
                                  List<LineaPedido> lineas) {
}
