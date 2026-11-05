package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pedido - agregado raiz y sus invariantes")
class PedidoTest {

    private static final Instant AHORA = Instant.parse("2026-11-03T10:00:00Z");

    private static Pedido pedidoNuevo() {
        return Pedido.crear(7L, "Ana Lopez", "ana@example.com",
                List.of(new LineaPedido("Teclado", 2,
                        Dinero.precioUnitario(new BigDecimal("45.9900"), "EUR"),
                        new BigDecimal("0.800"))),
                "43840", null, AHORA);
    }

    @Test
    @DisplayName("al crear: estado PENDIENTE, sin coste de envio y sin total")
    void estadoInicial() {
        Pedido pedido = pedidoNuevo();

        assertThat(pedido.estado()).isEqualTo(EstadoPedido.PENDIENTE);
        assertThat(pedido.costeEnvio()).isNull();
        assertThat(pedido.importeTotal()).isNull();
        assertThat(pedido.plazoEnvioDias()).isNull();
        assertThat(pedido.importeProductos().importe().toPlainString()).isEqualTo("91.98");
        assertThat(pedido.pesoTotalKg().toPlainString()).isEqualTo("1.600");
        assertThat(pedido.numBultos()).isEqualTo(2);
    }

    @Test
    @DisplayName("al confirmar: guarda el coste, el plazo y calcula el total")
    void confirmar() {
        Pedido pedido = pedidoNuevo().marcarConfirmando(AHORA);

        Pedido confirmado = pedido.confirmar(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2, AHORA);

        assertThat(confirmado.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(confirmado.costeEnvio().importe().toPlainString()).isEqualTo("7.95");
        assertThat(confirmado.importeTotal().importe().toPlainString()).isEqualTo("99.93");
        assertThat(confirmado.plazoEnvioDias()).isEqualTo(2);
        assertThat(confirmado.fechaConfirmacion()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("confirmar sin haber tomado el estado CONFIRMANDO es un error de transicion")
    void confirmarSinMarcar() {
        Pedido pedido = pedidoNuevo();

        assertThatThrownBy(() -> pedido.confirmar(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2, AHORA))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class)
                .hasMessageContaining("CONFIRMANDO");
    }

    @Test
    @DisplayName("un fallo del proveedor devuelve el pedido a PENDIENTE y deja constancia del error")
    void falloDevuelveAPendiente() {
        Pedido pedido = pedidoNuevo().marcarConfirmando(AHORA);

        Pedido revertido = pedido.devolverAPendiente("503 Service Unavailable");

        assertThat(revertido.estado()).isEqualTo(EstadoPedido.PENDIENTE);
        assertThat(revertido.numeroIntentosConfirmacion()).isEqualTo(1);
        assertThat(revertido.ultimoError()).isEqualTo("503 Service Unavailable");
        assertThat(revertido.costeEnvio()).isNull();
        assertThat(revertido.importeTotal()).isNull();
    }

    @Test
    @DisplayName("cancelar solo es valido desde PENDIENTE")
    void cancelar() {
        Pedido pendiente = pedidoNuevo();
        Pedido confirmado = pedidoNuevo().marcarConfirmando(AHORA)
                .confirmar(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2, AHORA);

        assertThat(pendiente.cancelar(AHORA).estado()).isEqualTo(EstadoPedido.CANCELADO);
        assertThatThrownBy(() -> confirmado.cancelar(AHORA))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }

    @Test
    @DisplayName("el total solo existe si hay coste de envio (no hay importes a medias)")
    void totalExigeCosteDeEnvio() {
        assertThatThrownBy(() -> Pedido.builder()
                .clienteId(1L)
                .clienteNombre("Ana")
                .clienteEmail("ana@example.com")
                .lineas(List.of(new LineaPedido("X", 1,
                        Dinero.precioUnitario(new BigDecimal("1.0000"), "EUR"), new BigDecimal("1.000"))))
                .codigoPostalDestino("43840")
                .estado(EstadoPedido.CONFIRMADO)
                .importeTotal(Dinero.importe(new BigDecimal("1.00"), "EUR"))
                .build())
                .hasMessageContaining("coste de envio");
    }
}
