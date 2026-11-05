package com.bdo.pedidos.api.persistencia;

import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de integracion del repositorio sobre H2 real (no mocks).
 *
 * <p>El comportamiento critico que se verifica es el <b>compare-and-set</b>: dos
 * actualizaciones concurrentes con la misma version esperada, solo una debe prosperar.
 * De ahi depende que dos confirmaciones simultaneas no llamen dos veces al proveedor.
 */
@SpringBootTest
@DisplayName("PedidoRepository - persistencia y transiciones atomicas")
class PedidoRepositoryTest {

    @Autowired
    private PedidoRepository repositorio;

    private Pedido pedidoNuevo() {
        return Pedido.crear(7L, "Ana Lopez", "ana@example.com",
                List.of(new LineaPedido("Teclado", 2,
                                Dinero.precioUnitario(new BigDecimal("45.9900"), "EUR"), new BigDecimal("0.800")),
                        new LineaPedido("Raton", 1,
                                Dinero.precioUnitario(new BigDecimal("19.5000"), "EUR"), new BigDecimal("0.100"))),
                "43840", null, Instant.parse("2026-11-03T10:00:00Z"));
    }

    @Test
    @DisplayName("inserta el pedido con sus lineas y lo devuelve con los importes calculados")
    void insertaYLee() {
        Pedido persistido = repositorio.insertar(pedidoNuevo()).block();

        assertThat(persistido).isNotNull();
        assertThat(persistido.id()).isNotNull();
        assertThat(persistido.importeProductos().importe().toPlainString()).isEqualTo("111.48");
        assertThat(persistido.pesoTotalKg().toPlainString()).isEqualTo("1.700");
        assertThat(persistido.numBultos()).isEqualTo(3);
        assertThat(persistido.estado()).isEqualTo(EstadoPedido.PENDIENTE);

        Pedido leido = repositorio.buscarPorId(persistido.id()).block();

        assertThat(leido).isNotNull();
        assertThat(leido.lineas()).hasSize(2);
        assertThat(leido.lineas().get(0).producto()).isEqualTo("Teclado");
        assertThat(leido.lineas().get(0).importeLinea().importe().toPlainString()).isEqualTo("91.98");
        assertThat(leido.lineas().get(1).importeLinea().importe().toPlainString()).isEqualTo("19.50");
        assertThat(leido.importeTotal()).isNull();
        assertThat(leido.costeEnvio()).isNull();
    }

    @Test
    @DisplayName("la transicion condicional solo se aplica si el estado y la version siguen siendo los esperados")
    void transicionCondicional() {
        Pedido persistido = repositorio.insertar(pedidoNuevo()).block();
        Pedido confirmando = persistido.marcarConfirmando(Instant.parse("2026-11-03T10:05:00Z"));

        boolean primera = repositorio.actualizarSiEstadoEs(persistido.id(), persistido.version(),
                EstadoPedido.PENDIENTE, confirmando).block();

        assertThat(primera).isTrue();

        // Segunda peticion con la misma version esperada: ya no aplica (compare-and-set).
        boolean segunda = repositorio.actualizarSiEstadoEs(persistido.id(), persistido.version(),
                EstadoPedido.PENDIENTE, confirmando).block();

        assertThat(segunda).isFalse();

        Pedido leido = repositorio.buscarPorId(persistido.id()).block();
        assertThat(leido.estado()).isEqualTo(EstadoPedido.CONFIRMANDO);
        assertThat(leido.version()).isEqualTo(persistido.version() + 1);
        assertThat(leido.fechaInicioConfirmacion()).isNotNull();
    }

    @Test
    @DisplayName("confirmar guarda el coste de envio y calcula el total")
    void confirmar() {
        Pedido persistido = repositorio.insertar(pedidoNuevo()).block();
        Pedido confirmando = persistido.marcarConfirmando(Instant.parse("2026-11-03T10:05:00Z"));
        repositorio.actualizarSiEstadoEs(persistido.id(), persistido.version(),
                EstadoPedido.PENDIENTE, confirmando).block();

        Pedido actual = repositorio.buscarPorId(persistido.id()).block();
        Pedido confirmado = actual.confirmar(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2,
                Instant.parse("2026-11-03T10:06:00Z"));

        boolean actualizado = repositorio.actualizarSiEstadoEs(actual.id(), actual.version(),
                EstadoPedido.CONFIRMANDO, confirmado).block();

        assertThat(actualizado).isTrue();

        Pedido leido = repositorio.buscarPorId(persistido.id()).block();
        assertThat(leido.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(leido.costeEnvio().importe().toPlainString()).isEqualTo("7.95");
        assertThat(leido.importeTotal().importe().toPlainString()).isEqualTo("119.43");
        assertThat(leido.plazoEnvioDias()).isEqualTo(2);
        assertThat(leido.fechaConfirmacion()).isNotNull();
    }

    @Test
    @DisplayName("la recuperacion devuelve a PENDIENTE las confirmaciones abandonadas")
    void recuperarConfirmacionesAbandonadas() {
        Pedido persistido = repositorio.insertar(pedidoNuevo()).block();
        Pedido confirmando = persistido.marcarConfirmando(Instant.parse("2026-11-03T10:05:00Z"));
        repositorio.actualizarSiEstadoEs(persistido.id(), persistido.version(),
                EstadoPedido.PENDIENTE, confirmando).block();

        // Un limite en el futuro "caduca" la confirmacion.
        Integer recuperados = repositorio.recuperarConfirmacionesAbandonadas(
                Instant.parse("2026-11-03T11:00:00Z")).block();

        assertThat(recuperados).isGreaterThanOrEqualTo(1);
        Pedido leido = repositorio.buscarPorId(persistido.id()).block();
        assertThat(leido.estado()).isEqualTo(EstadoPedido.PENDIENTE);
        assertThat(leido.ultimoError()).contains("abandonada");
    }
}
