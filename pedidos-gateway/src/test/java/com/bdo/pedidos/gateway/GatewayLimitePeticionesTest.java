package com.bdo.pedidos.gateway;

import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** Limite de peticiones global del gateway, con un limite minusculo y periodo largo. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SoporteGateway.DecoderDePrueba.class)
@DisplayName("API Gateway - limite de peticiones")
class GatewayLimitePeticionesTest {

    private static final WireMockServer MICROSERVICIO = SoporteGateway.arrancarMicroservicio();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        SoporteGateway.rutaAlMicroservicio(registro, MICROSERVICIO);
        registro.add("resilience4j.ratelimiter.instances.apiGateway.limit-for-period", () -> "2");
        registro.add("resilience4j.ratelimiter.instances.apiGateway.limit-refresh-period", () -> "1m");
    }

    @AfterAll
    static void parar() {
        MICROSERVICIO.stop();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("al superar el limite responde 429 con Retry-After, sin encolar")
    void responde429() {
        MICROSERVICIO.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(okJson("{\"id\":1}")));
        String token = "Bearer " + TokenDePrueba.token("pedidos.read");

        List<Integer> estados = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            estados.add(webTestClient.get().uri("/api/pedidos/1").header("Authorization", token)
                    .exchange().returnResult(String.class).getStatus().value());
        }

        assertThat(estados).containsExactly(200, 200, 429, 429);
        webTestClient.get().uri("/api/pedidos/1").header("Authorization", token)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1");
    }
}
