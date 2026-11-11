package com.bdo.pedidos.api.eventos;

import com.bdo.pedidos.dominio.modelo.Cliente;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import com.bdo.pedidos.testkit.TokenDePrueba;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Publicacion de PedidoConfirmado de extremo a extremo dentro del servicio: confirmacion
 * por HTTP, proveedor simulado con WireMock y el binder de test de Spring Cloud Stream
 * (en memoria) en lugar de RabbitMQ. Se lee el mensaje tal y como saldria al broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.eventos.habilitados=true")
@Import({TestChannelBinderConfiguration.class, PublicacionEventosIntegrationTest.DecoderDePrueba.class})
@DisplayName("Eventos - publicacion de PedidoConfirmado con Spring Cloud Stream")
class PublicacionEventosIntegrationTest {

    private static final String DESTINO = "pedidos.confirmados";
    private static final WireMockServer PROVEEDOR = new WireMockServer(options().dynamicPort());

    static {
        PROVEEDOR.start();
    }

    @AfterAll
    static void parar() {
        PROVEEDOR.stop();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("app.proveedor.url", () -> "http://localhost:" + PROVEEDOR.port() + "/api/tarifas");
    }

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
    private OutputDestination salida;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private ClientePort clientes;

    @BeforeEach
    void preparar() {
        PROVEEDOR.resetAll();
        salida.clear();
        when(clientes.buscarPorId(anyLong()))
                .thenReturn(Mono.just(new Cliente(7L, "Ana Lopez", "ana@example.com")));
    }

    private WebTestClient autorizado() {
        return webTestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + TokenDePrueba.token("pedidos.write"))
                .build();
    }

    private long crearPedido() {
        Map<?, ?> pedido = autorizado().post().uri("/api/pedidos")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"clienteId": 7, "codigoPostalDestino": "43840",
                         "lineas": [{"producto": "Teclado", "cantidad": 2, "precioUnitario": 45.99, "pesoUnitarioKg": 0.8}]}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) pedido.get("id")).longValue();
    }

    @Test
    @DisplayName("al confirmar se publica un unico PedidoConfirmado con los importes como texto decimal exacto")
    void publicaAlConfirmar() throws Exception {
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));
        long id = crearPedido();

        autorizado().post().uri("/api/pedidos/{id}/confirmacion", id).exchange().expectStatus().isOk();

        Message<byte[]> mensaje = salida.receive(5000, DESTINO);
        assertThat(mensaje).isNotNull();
        assertThat(mensaje.getHeaders().get("tipoEvento")).isEqualTo("PedidoConfirmado");
        assertThat(mensaje.getHeaders().get("pedidoId")).isEqualTo(id);

        JsonNode evento = json.readTree(mensaje.getPayload());
        assertThat(evento.get("pedidoId").asLong()).isEqualTo(id);
        assertThat(evento.get("clienteId").asLong()).isEqualTo(7L);
        assertThat(evento.get("importeProductos").decimalValue()).isEqualByComparingTo("91.98");
        assertThat(evento.get("costeEnvio").decimalValue()).isEqualByComparingTo("7.95");
        assertThat(evento.get("importeTotal").decimalValue()).isEqualByComparingTo("99.93");
        assertThat(evento.get("moneda").asText()).isEqualTo("EUR");

        // La confirmacion idempotente no vuelve a publicar.
        autorizado().post().uri("/api/pedidos/{id}/confirmacion", id).exchange().expectStatus().isOk();
        assertThat(salida.receive(500, DESTINO)).isNull();
    }

    @Test
    @DisplayName("si el proveedor falla, el pedido no se confirma y no se publica nada")
    void sinConfirmacionNoHayEvento() {
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(aResponse().withStatus(400)));
        long id = crearPedido();

        autorizado().post().uri("/api/pedidos/{id}/confirmacion", id).exchange().expectStatus().isEqualTo(502);

        assertThat(salida.receive(500, DESTINO)).isNull();
    }
}
