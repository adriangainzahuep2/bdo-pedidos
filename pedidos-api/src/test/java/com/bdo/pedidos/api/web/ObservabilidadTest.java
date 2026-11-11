package com.bdo.pedidos.api.web;

import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observabilidad con la instrumentacion real activada ({@link AutoConfigureObservability}:
 * sin ella, Spring Boot desactiva trazas y exportacion de metricas en los tests).
 *
 * <p>El servicio de clientes y el proveedor son WireMock reales, para comprobar lo que de
 * verdad viaja por la red: la cabecera W3C {@code traceparent} y el baggage
 * {@code X-Correlation-Id}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Observabilidad - trazas distribuidas, correlacion en logs y metricas Prometheus")
class ObservabilidadTest {

    /** traceparent W3C: version-traceId(32 hex)-spanId(16 hex)-flags. */
    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]";

    private static final WireMockServer CLIENTES = new WireMockServer(options().dynamicPort());
    private static final WireMockServer PROVEEDOR = new WireMockServer(options().dynamicPort());

    static {
        CLIENTES.start();
        PROVEEDOR.start();
    }

    @AfterAll
    static void parar() {
        CLIENTES.stop();
        PROVEEDOR.stop();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("app.clientes.url", () -> "http://localhost:" + CLIENTES.port());
        registro.add("app.proveedor.url", () -> "http://localhost:" + PROVEEDOR.port() + "/api/tarifas");
        // Sin servidor Zipkin en los tests: los spans se generan y propagan igual.
        registro.add("management.zipkin.tracing.endpoint", () -> "http://localhost:1/api/v2/spans");
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

    @LocalServerPort
    private int puerto;

    @BeforeEach
    void preparar() {
        CLIENTES.resetAll();
        PROVEEDOR.resetAll();
        CLIENTES.stubFor(get(urlMatching("/api/clientes/.*")).willReturn(okJson(
                "{\"id\":1,\"nombre\":\"Ana Lopez\",\"email\":\"ana@example.com\"}")));
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson(
                "{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));
    }

    private static final String PEDIDO = """
            {"clienteId": 1, "codigoPostalDestino": "43840",
             "lineas": [{"producto": "Teclado", "cantidad": 2, "precioUnitario": 45.99, "pesoUnitarioKg": 0.8}]}
            """;

    private long crearPedido(String correlacion) {
        Number id = webTestClient.post().uri("/api/pedidos")
                .header("Authorization", "Bearer " + TokenDePrueba.token("pedidos.write"))
                .header("X-Correlation-Id", correlacion)
                .header("Content-Type", "application/json")
                .bodyValue(PEDIDO)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(java.util.Map.class).returnResult().getResponseBody().get("id") instanceof Number n ? n : null;
        assertThat(id).isNotNull();
        return id.longValue();
    }

    @Test
    @DisplayName("la llamada al servicio de clientes lleva traceparent W3C y el X-Correlation-Id como baggage")
    void propagaLaTrazaAlServicioDeClientes() {
        crearPedido("obs-clientes-1");

        List<LoggedRequest> llamadas = CLIENTES.findAll(anyRequestedFor(urlMatching("/api/clientes/.*")));
        assertThat(llamadas).hasSize(1);
        assertThat(llamadas.get(0).getHeader("traceparent")).matches(TRACEPARENT);
        assertThat(llamadas.get(0).getHeader("X-Correlation-Id")).isEqualTo("obs-clientes-1");
    }

    @Test
    @DisplayName("la llamada al proveedor forma parte de la traza de la confirmacion")
    void propagaLaTrazaAlProveedor() {
        long id = crearPedido("obs-proveedor-1");

        // WebClient sin instrumentar: el WebTestClient de Boot si lo esta y sustituiria el
        // traceparent por el suyo. Asi la peticion llega con una traza conocida.
        int estado = WebClient.create("http://localhost:" + puerto)
                .post().uri("/api/pedidos/{id}/confirmacion", id)
                .header("Authorization", "Bearer " + TokenDePrueba.token("pedidos.write"))
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .exchangeToMono(respuesta -> respuesta.releaseBody().thenReturn(respuesta.statusCode().value()))
                .block();
        assertThat(estado).isEqualTo(200);

        List<LoggedRequest> llamadas = PROVEEDOR.findAll(anyRequestedFor(urlEqualTo("/api/tarifas")));
        assertThat(llamadas).hasSize(1);
        // Mismo traceId que la peticion entrante: un span hijo, no una traza nueva.
        assertThat(llamadas.get(0).getHeader("traceparent"))
                .matches(TRACEPARENT)
                .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-")
                .doesNotContain("00f067aa0ba902b7");
    }

    @Test
    @DisplayName("cada linea de log lleva traceId, spanId y el X-Correlation-Id")
    void correlacionEnLosLogs(CapturedOutput salida) {
        long id = crearPedido("obs-log-1");

        String linea = salida.getOut().lines()
                .filter(l -> l.contains("Pedido " + id + " creado"))
                .findFirst().orElseThrow();
        assertThat(linea).containsPattern("\\[pedidos-api,[0-9a-f]{32},[0-9a-f]{16},obs-log-1\\]");
    }

    @Test
    @DisplayName("/actuator/prometheus es publico y expone circuitos de Resilience4j e histogramas HTTP")
    void metricasPrometheus() {
        crearPedido("obs-metricas-1");

        String metricas = webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(metricas)
                .contains("resilience4j_circuitbreaker_state{application=\"pedidos-api\"")
                .contains("name=\"proveedorLogistico\"")
                .contains("name=\"servicioClientes\"")
                .contains("http_server_requests_seconds_bucket{application=\"pedidos-api\"");
    }
}
