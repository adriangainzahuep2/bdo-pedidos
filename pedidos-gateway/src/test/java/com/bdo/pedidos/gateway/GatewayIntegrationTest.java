package com.bdo.pedidos.gateway;

import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El gateway de verdad (puerto aleatorio, cadena de seguridad completa) delante de un
 * microservicio simulado con WireMock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SoporteGateway.DecoderDePrueba.class)
@DisplayName("API Gateway - autenticacion, correlacion y enrutado")
class GatewayIntegrationTest {

    private static final WireMockServer MICROSERVICIO = SoporteGateway.arrancarMicroservicio();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        SoporteGateway.rutaAlMicroservicio(registro, MICROSERVICIO);
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
        MICROSERVICIO.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(okJson("{\"id\":1}")));
    }

    private static String token() {
        return "Bearer " + TokenDePrueba.token("pedidos.read");
    }

    @Test
    @DisplayName("sin token responde 401 y la peticion no llega al microservicio")
    void sinToken() {
        webTestClient.get().uri("/api/pedidos/1").exchange().expectStatus().isUnauthorized();

        MICROSERVICIO.verify(exactly(0), getRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("con token enruta, y el identificador de correlacion generado llega al microservicio y vuelve al cliente")
    void enrutaYPropagaLaCorrelacion() {
        List<String> devuelto = webTestClient.get().uri("/api/pedidos/1")
                .header("Authorization", token())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(1)
                .returnResult().getResponseHeaders().get("X-Correlation-Id");

        assertThat(devuelto).hasSize(1);
        String identificador = devuelto.get(0);
        assertThat(identificador).matches("[0-9a-f-]{36}");
        MICROSERVICIO.verify(getRequestedFor(urlEqualTo("/api/pedidos/1"))
                .withHeader("X-Correlation-Id", equalTo(identificador))
                .withHeader("Authorization", matching("Bearer .+")));
    }

    @Test
    @DisplayName("respeta el identificador de correlacion que envia el cliente")
    void respetaLaCorrelacionDelCliente() {
        webTestClient.get().uri("/api/pedidos/1")
                .header("Authorization", token())
                .header("X-Correlation-Id", "peticion-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "peticion-123");

        MICROSERVICIO.verify(getRequestedFor(urlEqualTo("/api/pedidos/1"))
                .withHeader("X-Correlation-Id", equalTo("peticion-123")));
    }

    @Test
    @DisplayName("no hay rutas automaticas por servicio: los servicios internos no se exponen")
    void sinRutasAutomaticas() {
        webTestClient.get().uri("/pedidos-auth/oauth2/jwks")
                .header("Authorization", token())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("la consola web (HTML, CSS y JavaScript) se sirve sin token; la API sigue exigiendolo")
    void consolaWebPublica() {
        MICROSERVICIO.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<h1>Consola de pedidos</h1>")));
        MICROSERVICIO.stubFor(get(urlEqualTo("/app.js")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/javascript").withBody("// consola")));

        webTestClient.get().uri("/").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("<h1>Consola de pedidos</h1>");
        webTestClient.get().uri("/app.js").exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/pedidos/1").exchange().expectStatus().isUnauthorized();
        // Solo GET: la ruta de la consola no admite escrituras.
        webTestClient.post().uri("/index.html").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("los endpoints de salud son publicos")
    void saludPublica() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
