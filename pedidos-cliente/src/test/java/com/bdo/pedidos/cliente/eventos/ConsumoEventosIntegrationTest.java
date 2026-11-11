package com.bdo.pedidos.cliente.eventos;

import com.bdo.pedidos.testkit.TokenDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumo de PedidoConfirmado con el binder de test de Spring Cloud Stream (en memoria):
 * el mensaje entra por el destino {@code pedidos.confirmados} exactamente como lo publica
 * pedidos-api (JSON con todos sus campos) y se comprueba el efecto en la proyeccion y en
 * la API de clientes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.eventos.habilitados=true",
        "spring.cloud.function.definition=pedidoConfirmado",
        // Reintentos rapidos en el test (en produccion: 0,5 s y 1 s).
        "spring.cloud.stream.bindings.pedidoConfirmado-in-0.consumer.back-off-initial-interval=10"})
@Import({TestChannelBinderConfiguration.class, ConsumoEventosIntegrationTest.DecoderDePrueba.class})
@DisplayName("Eventos - consumo de PedidoConfirmado y resumen por cliente")
class ConsumoEventosIntegrationTest {

    private static final String DESTINO = "pedidos.confirmados";
    private static final AtomicLong PEDIDOS = new AtomicLong(50_000);

    @TestConfiguration
    static class DecoderDePrueba {

        @Bean
        @Primary
        ReactiveJwtDecoder reactiveJwtDecoderDePrueba() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(TokenDePrueba.CLAVE)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenDePrueba.EMISOR));
            return decoder;
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InputDestination entrada;

    @Autowired
    private DatabaseClient baseDeDatos;

    private WebTestClient autorizado(String... permisos) {
        return webTestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + TokenDePrueba.token(permisos))
                .build();
    }

    private long crearCliente() {
        Map<?, ?> cliente = autorizado("clientes.write").post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombre\":\"Cliente Eventos\",\"email\":\"eventos-" + System.nanoTime() + "@bdo-es.example\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) cliente.get("id")).longValue();
    }

    /** El JSON completo que publica pedidos-api, con campos que este servicio no usa. */
    private void publicar(long pedidoId, long clienteId, String total) {
        String json = """
                {"pedidoId": %d, "clienteId": %d, "importeProductos": 91.98, "costeEnvio": 7.95,
                 "importeTotal": %s, "moneda": "EUR", "plazoEnvioDias": 2,
                 "fechaConfirmacion": "2026-11-03T10:00:00Z"}
                """.formatted(pedidoId, clienteId, total);
        entrada.send(MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .setHeader("tipoEvento", "PedidoConfirmado")
                .build(), DESTINO);
    }

    private Map<?, ?> resumen(long clienteId) {
        return autorizado("clientes.read").get().uri("/api/clientes/{id}/pedidos-confirmados", clienteId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @Test
    @DisplayName("cada evento suma un pedido confirmado al resumen del cliente (campos desconocidos se ignoran)")
    void sumaPedidosConfirmados() {
        long cliente = crearCliente();

        publicar(PEDIDOS.incrementAndGet(), cliente, "99.93");
        publicar(PEDIDOS.incrementAndGet(), cliente, "21.07");

        // JSON en crudo: el importe sale con sus dos decimales (BigDecimal), no como 121.0.
        String json = autorizado("clientes.read").get().uri("/api/clientes/{id}/pedidos-confirmados", cliente)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(json).isEqualTo(
                "{\"clienteId\":" + cliente + ",\"pedidosConfirmados\":2,\"importeConfirmado\":{\"EUR\":121.00}}");
    }

    @Test
    @DisplayName("un evento repetido (entrega al menos una vez) no se cuenta dos veces")
    void idempotente() {
        long cliente = crearCliente();
        long pedido = PEDIDOS.incrementAndGet();

        publicar(pedido, cliente, "99.93");
        publicar(pedido, cliente, "99.93");

        assertThat(((Number) resumen(cliente).get("pedidosConfirmados")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("un cliente sin pedidos tiene el resumen vacio; uno inexistente responde 404")
    void resumenVacioYClienteInexistente() {
        long cliente = crearCliente();

        Map<?, ?> resumen = resumen(cliente);
        assertThat(((Number) resumen.get("pedidosConfirmados")).longValue()).isZero();
        assertThat((Map<?, ?>) resumen.get("importeConfirmado")).isEmpty();

        autorizado("clientes.read").get().uri("/api/clientes/{id}/pedidos-confirmados", 999_999)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("un evento que no se puede guardar (cliente desconocido) se reintenta y no deja datos a medias")
    void eventoDeClienteDesconocido() {
        long pedido = PEDIDOS.incrementAndGet();

        // El binder reintenta (3 intentos) y despues lo entrega al canal de errores (en
        // RabbitMQ, a la cola de mensajes muertos). El servicio sigue funcionando.
        try {
            publicar(pedido, 999_999L, "10.00");
        } catch (RuntimeException esperado) {
            // El binder de test propaga el error al emisor; con RabbitMQ va a la DLQ.
        }

        Long filas = baseDeDatos.sql("SELECT count(*) FROM cliente_pedido_confirmado WHERE pedido_id = :id")
                .bind("id", pedido)
                .map((fila, meta) -> fila.get(0, Long.class))
                .one().block();
        assertThat(filas).isZero();
        assertThat(((Number) resumen(crearCliente()).get("pedidosConfirmados")).longValue()).isZero();
    }
}
