package com.bdo.pedidos.dominio.evento;

import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Evento de dominio: un pedido ha quedado CONFIRMADO, con su coste de envio y su total.
 *
 * <p>Es un hecho consumado (en pasado) y autocontenido: lleva los datos que necesita quien
 * lo escuche, para que no tenga que volver a preguntar al servicio de pedidos. Se publica
 * de forma asincrona (Spring Cloud Stream); hoy lo consume el servicio de clientes para
 * mantener el resumen de pedidos confirmados de cada cliente.
 *
 * <p>Los importes viajan como {@link BigDecimal} con su moneda, igual que en el modelo:
 * nunca como {@code double}.
 */
public record PedidoConfirmado(
        long pedidoId,
        long clienteId,
        BigDecimal importeProductos,
        BigDecimal costeEnvio,
        BigDecimal importeTotal,
        String moneda,
        int plazoEnvioDias,
        Instant fechaConfirmacion) {

    public PedidoConfirmado {
        Objects.requireNonNull(importeProductos, "importeProductos");
        Objects.requireNonNull(costeEnvio, "costeEnvio");
        Objects.requireNonNull(importeTotal, "importeTotal");
        Objects.requireNonNull(moneda, "moneda");
        Objects.requireNonNull(fechaConfirmacion, "fechaConfirmacion");
    }

    /**
     * Construye el evento a partir de un pedido confirmado.
     *
     * @throws TransicionDeEstadoInvalidaException si el pedido no esta CONFIRMADO: el evento
     *         no puede anunciar algo que no ha ocurrido.
     */
    public static PedidoConfirmado de(Pedido pedido) {
        if (pedido.estado() != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException(
                    "Solo se publica PedidoConfirmado de un pedido CONFIRMADO (el " + pedido.id()
                            + " esta en " + pedido.estado() + ")");
        }
        return new PedidoConfirmado(
                pedido.id(),
                pedido.clienteId(),
                pedido.importeProductos().importe(),
                pedido.costeEnvio().importe(),
                pedido.importeTotal().importe(),
                pedido.importeTotal().moneda(),
                pedido.plazoEnvioDias(),
                pedido.fechaConfirmacion());
    }
}
