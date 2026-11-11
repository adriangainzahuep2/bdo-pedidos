package com.bdo.pedidos.gateway;

import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** Trazas y metricas del gateway, con la instrumentacion real activada. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(SoporteGateway.DecoderDePrueba.class)
@DisplayName("API Gateway - trazas distribuidas y metricas")
class GatewayObservabilidadTest {

    private static final WireMockServer MICROSERVICIO = SoporteGateway.arrancarMicroservicio();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        SoporteGateway.rutaAlMicroservicio(registro, MICROSERVICIO);
        registro.add("spring.cloud.gateway.routes[0].filters[0].name", () -> "CircuitBreaker");
        registro.add("spring.cloud.gateway.routes[0].filters[0].args.name", () -> "pedidosApi");
        registro.add("management.zipkin.tracing.endpoint", () -> "http://localhost:1/api/v2/spans");
    }

    @AfterAll
    static void parar() {
        MICROSERVICIO.stop();
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void preparar() {
        MICROSERVICIO.resetAll();
    }

    @Test
    @DisplayName("el gateway propaga al microservicio la traza (traceparent W3C) y la correlacion")
    void propagaLaTraza() {
        MICROSERVICIO.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(okJson("{\"id\":1}")));

        webTestClient.get().uri("/api/pedidos/1")
                .header("Authorization", "Bearer " + TokenDePrueba.token("pedidos.read"))
                .header("X-Correlation-Id", "gw-traza-1")
                .exchange()
                .expectStatus().isOk();

        List<LoggedRequest> llamadas = MICROSERVICIO.findAll(anyRequestedFor(urlMatching("/api/pedidos/.*")));
        assertThat(llamadas).hasSize(1);
        assertThat(llamadas.get(0).getHeader("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(llamadas.get(0).getHeader("X-Correlation-Id")).isEqualTo("gw-traza-1");
    }

    @Test
    @DisplayName("/actuator/prometheus es publico y expone el estado de los circuitos y el limitador")
    void metricasPrometheus() {
        MICROSERVICIO.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(okJson("{\"id\":1}")));
        webTestClient.get().uri("/api/pedidos/1")
                .header("Authorization", "Bearer " + TokenDePrueba.token("pedidos.read"))
                .exchange().expectStatus().isOk();

        String metricas = webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(metricas)
                .contains("resilience4j_circuitbreaker_state{application=\"pedidos-gateway\"")
                .contains("name=\"pedidosApi\"")
                .contains("resilience4j_ratelimiter_available_permissions{application=\"pedidos-gateway\"")
                .contains("spring_cloud_gateway_requests_seconds_count{application=\"pedidos-gateway\"");
    }
}
