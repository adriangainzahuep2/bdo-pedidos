package com.bdo.pedidos.api.eventos;

import com.bdo.pedidos.dominio.evento.PedidoConfirmado;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.cloud.stream.function.StreamBridge;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PublicadorEventosStream - publicacion de mejor esfuerzo")
class PublicadorEventosStreamTest {

    private static final PedidoConfirmado EVENTO = new PedidoConfirmado(1L, 7L,
            new BigDecimal("91.98"), new BigDecimal("7.95"), new BigDecimal("99.93"), "EUR", 2,
            Instant.parse("2026-11-03T10:00:00Z"));

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private final PublicadorEventosStream publicador = new PublicadorEventosStream(streamBridge, metricas);

    private double contador(String resultado) {
        return metricas.get("pedidos.eventos.publicados").tag("resultado", resultado).counter().count();
    }

    @Test
    @DisplayName("publica en el binding de salida y lo cuenta como ok")
    void publica() {
        when(streamBridge.send(eq(PublicadorEventosStream.BINDING), any(Object.class))).thenReturn(true);

        StepVerifier.create(publicador.publicar(EVENTO)).verifyComplete();

        assertThat(contador("ok")).isEqualTo(1.0);
        assertThat(contador("error")).isZero();
    }

    @Test
    @DisplayName("con el broker caido completa sin error (el pedido ya esta confirmado) y lo cuenta como error")
    void brokerCaido() {
        when(streamBridge.send(eq(PublicadorEventosStream.BINDING), any(Object.class)))
                .thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        StepVerifier.create(publicador.publicar(EVENTO)).verifyComplete();

        assertThat(contador("error")).isEqualTo(1.0);
        assertThat(contador("ok")).isZero();
    }

    @Test
    @DisplayName("si el binder no acepta el mensaje, completa y lo cuenta como error")
    void noAceptado() {
        when(streamBridge.send(eq(PublicadorEventosStream.BINDING), any(Object.class))).thenReturn(false);

        StepVerifier.create(publicador.publicar(EVENTO)).verifyComplete();

        assertThat(contador("error")).isEqualTo(1.0);
    }
}
