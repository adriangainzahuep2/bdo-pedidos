package com.bdo.pedidos.api.web.dto;

import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Respuesta de la API de pedidos.
 *
 * <p>Los importes se serializan como {@link BigDecimal} (numero JSON con escala exacta,
 * nunca notacion cientifica ni {@code double}). El importe total solo aparece cuando el
 * pedido esta confirmado: si el pedido esta pendiente, {@code importeTotal} es {@code null}.
 */
public record PedidoResponse(Long id,
                             long clienteId,
                             String clienteNombre,
                             String clienteEmail,
                             String estado,
                             Instant fechaCreacion,
                             Instant fechaConfirmacion,
                             List<LineaPedidoResponse> lineas,
                             BigDecimal importeProductos,
                             BigDecimal costeEnvio,
                             BigDecimal importeTotal,
                             String moneda,
                             BigDecimal pesoTotalKg,
                             int numBultos,
                             String codigoPostalDestino,
                             Integer plazoEnvioDias,
                             int numeroIntentosConfirmacion,
                             String ultimoError,
                             long version) {

    public static PedidoResponse desde(Pedido pedido) {
        return new PedidoResponse(
                pedido.id(),
                pedido.clienteId(),
                pedido.clienteNombre(),
                pedido.clienteEmail(),
                pedido.estado().name(),
                pedido.fechaCreacion(),
                pedido.fechaConfirmacion(),
                pedido.lineas().stream().map(LineaPedidoResponse::desde).toList(),
                importe(pedido.importeProductos()),
                importe(pedido.costeEnvio()),
                importe(pedido.importeTotal()),
                pedido.importeProductos().moneda(),
                pedido.pesoTotalKg(),
                pedido.numBultos(),
                pedido.codigoPostalDestino(),
                pedido.plazoEnvioDias(),
                pedido.numeroIntentosConfirmacion(),
                pedido.ultimoError(),
                pedido.version());
    }

    private static BigDecimal importe(Dinero dinero) {
        return dinero == null ? null : dinero.importe();
    }

    /** Linea de pedido de la respuesta. */
    public record LineaPedidoResponse(String producto,
                                      int cantidad,
                                      BigDecimal precioUnitario,
                                      BigDecimal pesoUnitarioKg,
                                      BigDecimal importeLinea) {

        public static LineaPedidoResponse desde(LineaPedido linea) {
            return new LineaPedidoResponse(
                    linea.producto(),
                    linea.cantidad(),
                    linea.precioUnitario().importe(),
                    linea.pesoUnitarioKg(),
                    linea.importeLinea().importe());
        }
    }
}
