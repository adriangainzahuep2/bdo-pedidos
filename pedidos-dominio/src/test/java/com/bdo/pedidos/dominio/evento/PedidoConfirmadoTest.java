package com.bdo.pedidos.dominio.evento;

import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PedidoConfirmado - evento de dominio")
class PedidoConfirmadoTest {

    private static final Instant AHORA = Instant.parse("2026-11-03T10:00:00Z");

    /** Pedido PENDIENTE ya persistido (con id), como lo devuelve el repositorio. */
    private static Pedido pedidoPendiente() {
        return Pedido.builder()
                .id(42L)
                .clienteId(7L)
                .clienteNombre("Ana Lopez")
                .clienteEmail("ana@example.com")
                .estado(EstadoPedido.PENDIENTE)
                .fechaCreacion(AHORA)
                .lineas(List.of(new LineaPedido("Teclado", 2,
                        Dinero.precioUnitario(new BigDecimal("45.9900"), "EUR"),
                        new BigDecimal("0.800"))))
                .importeProductos(Dinero.importe(new BigDecimal("91.98"), "EUR"))
                .pesoTotalKg(new BigDecimal("1.600"))
                .numBultos(2)
                .codigoPostalDestino("43840")
                .version(0L)
                .numeroIntentosConfirmacion(0)
                .build();
    }

    @Test
    @DisplayName("de un pedido confirmado lleva sus importes, plazo y fecha, sin perder la escala")
    void deUnPedidoConfirmado() {
        Pedido confirmado = pedidoPendiente().marcarConfirmando(AHORA)
                .confirmar(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2, AHORA);

        PedidoConfirmado evento = PedidoConfirmado.de(confirmado);

        assertThat(evento.pedidoId()).isEqualTo(42L);
        assertThat(evento.clienteId()).isEqualTo(7L);
        assertThat(evento.importeProductos().toPlainString()).isEqualTo("91.98");
        assertThat(evento.costeEnvio().toPlainString()).isEqualTo("7.95");
        assertThat(evento.importeTotal().toPlainString()).isEqualTo("99.93");
        assertThat(evento.moneda()).isEqualTo("EUR");
        assertThat(evento.plazoEnvioDias()).isEqualTo(2);
        assertThat(evento.fechaConfirmacion()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("no se puede anunciar la confirmacion de un pedido que no esta CONFIRMADO")
    void soloDePedidosConfirmados() {
        assertThatThrownBy(() -> PedidoConfirmado.de(pedidoPendiente()))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class)
                .hasMessageContaining("PENDIENTE");
    }
}
