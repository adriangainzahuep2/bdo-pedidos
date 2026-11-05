package com.bdo.pedidos.dominio.modelo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;


import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EstadoPedido - maquina de estados del pedido")
class EstadoPedidoTest {

    @Test
    @DisplayName("PENDIENTE puede pasar a CONFIRMANDO y a CANCELADO")
    void transicionesDesdePendiente() {
        assertThat(EstadoPedido.PENDIENTE.puedeTransicionarA(EstadoPedido.CONFIRMANDO)).isTrue();
        assertThat(EstadoPedido.PENDIENTE.puedeTransicionarA(EstadoPedido.CANCELADO)).isTrue();
        assertThat(EstadoPedido.PENDIENTE.puedeTransicionarA(EstadoPedido.CONFIRMADO)).isFalse();
        assertThat(EstadoPedido.PENDIENTE.puedeTransicionarA(EstadoPedido.PENDIENTE)).isFalse();
    }

    @Test
    @DisplayName("CONFIRMANDO puede pasar a CONFIRMADO (exito) o volver a PENDIENTE (fallo)")
    void transicionesDesdeConfirmando() {
        assertThat(EstadoPedido.CONFIRMANDO.puedeTransicionarA(EstadoPedido.CONFIRMADO)).isTrue();
        assertThat(EstadoPedido.CONFIRMANDO.puedeTransicionarA(EstadoPedido.PENDIENTE)).isTrue();
        assertThat(EstadoPedido.CONFIRMANDO.puedeTransicionarA(EstadoPedido.CANCELADO)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"CONFIRMADO", "CANCELADO"})
    @DisplayName("los estados terminales no admiten ninguna transicion")
    void estadosTerminales(EstadoPedido estado) {
        assertThat(estado.esTerminal()).isTrue();
        assertThat(estado.transicionesPermitidas()).isEmpty();
    }

    @Test
    @DisplayName("CONFIRMANDO es el unico estado transitorio")
    void unicoEstadoTransitorio() {
        assertThat(EstadoPedido.CONFIRMANDO.esTransitorio()).isTrue();
        assertThat(EstadoPedido.PENDIENTE.esTransitorio()).isFalse();
        assertThat(EstadoPedido.CONFIRMADO.esTransitorio()).isFalse();
    }
}
