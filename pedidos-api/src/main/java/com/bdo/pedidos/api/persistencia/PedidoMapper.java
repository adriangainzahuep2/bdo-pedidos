package com.bdo.pedidos.api.persistencia;

import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;
import io.r2dbc.spi.Row;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Traduccion entre las filas de base de datos y el modelo de dominio.
 *
 * <p>Decisiones:
 * <ul>
 *   <li>Los {@code TIMESTAMP WITH TIME ZONE} se leen como {@link OffsetDateTime} y se
 *       convierten a {@link Instant}: el instante es el unico tipo que representa un punto
 *       en el tiempo sin ambiguedad, y evita arrastrar la zona horaria del servidor.</li>
 *   <li>Los importes se normalizan a 2 decimales al leerlos: el driver puede devolver
 *       {@code 91.980000} en lugar de {@code 91.98} y la aritmetica monetaria debe ser
 *       siempre exacta y con escala estable.</li>
 * </ul>
 */
public final class PedidoMapper {

    private PedidoMapper() {
    }

    /**
     * Lee la fila de {@code pedido} en un constructor del agregado (sin lineas todavia).
     * Debe llamarse dentro del callback de mapeo de R2DBC: la fila no es valida fuera de el.
     * El agregado se valida al llamar a {@code build()}, una vez anadidas las lineas.
     */
    public static Pedido.Builder aBuilder(Row fila) {
        return Pedido.builder()
                .id(fila.get("id", Long.class))
                .clienteId(fila.get("cliente_id", Long.class))
                .clienteNombre(fila.get("cliente_nombre", String.class))
                .clienteEmail(fila.get("cliente_email", String.class))
                .estado(EstadoPedido.valueOf(fila.get("estado", String.class)))
                .fechaCreacion(instant(fila, "fecha_creacion"))
                .fechaConfirmacion(instantOpcional(fila, "fecha_confirmacion"))
                .fechaInicioConfirmacion(instantOpcional(fila, "fecha_inicio_confirmacion"))
                .importeProductos(dinero(fila.get("importe_productos", BigDecimal.class)))
                .costeEnvio(dineroOpcional(fila.get("coste_envio", BigDecimal.class)))
                .importeTotal(dineroOpcional(fila.get("importe_total", BigDecimal.class)))
                .pesoTotalKg(fila.get("peso_total_kg", BigDecimal.class))
                .numBultos(fila.get("num_bultos", Integer.class))
                .codigoPostalDestino(fila.get("codigo_postal_destino", String.class))
                .plazoEnvioDias(fila.get("plazo_envio_dias", Integer.class))
                .version(fila.get("version", Long.class))
                .numeroIntentosConfirmacion(fila.get("num_intentos_confirmacion", Integer.class))
                .ultimoError(fila.get("ultimo_error", String.class));
    }

    /** Construye una linea de pedido a partir de su fila. */
    public static LineaPedido aLinea(Row fila) {
        return new LineaPedido(
                fila.get("producto", String.class),
                fila.get("cantidad", Integer.class),
                Dinero.precioUnitario(fila.get("precio_unitario", BigDecimal.class), "EUR"),
                fila.get("peso_unitario_kg", BigDecimal.class));
    }

    /** Devuelve una copia del pedido con identificador asignado (tras el INSERT). */
    public static Pedido conIdentificador(Pedido pedido, long id) {
        return Pedido.builder()
                .id(id)
                .clienteId(pedido.clienteId())
                .clienteNombre(pedido.clienteNombre())
                .clienteEmail(pedido.clienteEmail())
                .estado(pedido.estado())
                .fechaCreacion(pedido.fechaCreacion())
                .fechaConfirmacion(pedido.fechaConfirmacion())
                .fechaInicioConfirmacion(pedido.fechaInicioConfirmacion())
                .lineas(pedido.lineas())
                .importeProductos(pedido.importeProductos())
                .costeEnvio(pedido.costeEnvio())
                .importeTotal(pedido.importeTotal())
                .pesoTotalKg(pedido.pesoTotalKg())
                .numBultos(pedido.numBultos())
                .codigoPostalDestino(pedido.codigoPostalDestino())
                .plazoEnvioDias(pedido.plazoEnvioDias())
                .version(pedido.version())
                .numeroIntentosConfirmacion(pedido.numeroIntentosConfirmacion())
                .ultimoError(pedido.ultimoError())
                .build();
    }

    private static Dinero dinero(BigDecimal valor) {
        return Dinero.importe(valor.setScale(Dinero.ESCALA_IMPORTE, RoundingMode.HALF_UP), "EUR");
    }

    private static Dinero dineroOpcional(BigDecimal valor) {
        return valor == null ? null : dinero(valor);
    }

    private static Instant instant(Row fila, String columna) {
        OffsetDateTime valor = fila.get(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static Instant instantOpcional(Row fila, String columna) {
        return instant(fila, columna);
    }

    /** Convierte un instante al parametro de timestamp con zona horaria usado en las escrituras. */
    public static OffsetDateTime aTimestamp(Instant instante) {
        return instante == null ? null : instante.atOffset(ZoneOffset.UTC);
    }
}
