package com.bdo.pedidos.gateway;

import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import java.io.IOException;
import java.net.ServerSocket;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtro CircuitBreaker de las rutas del gateway: pedidos-api simulado con WireMock y el
 * servicio de clientes "caido" (un puerto en el que no escucha nadie).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SoporteGateway.DecoderDePrueba.class)
@DisplayName("API Gateway - circuit breaker por microservicio")
class GatewayCircuitBreakerTest {

    private static final WireMockServer PEDIDOS = SoporteGateway.arrancarMicroservicio();
    private static final int PUERTO_CERRADO = puertoLibre();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.cloud.gateway.routes[0].id", () -> "pedidos-api");
        registro.add("spring.cloud.gateway.routes[0].uri", () -> "http://localhost:" + PEDIDOS.port());
        registro.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/api/pedidos/**");
        registro.add("spring.cloud.gateway.routes[0].filters[0].name", () -> "CircuitBreaker");
        registro.add("spring.cloud.gateway.routes[0].filters[0].args.name", () -> "pedidosApi");
        registro.add("spring.cloud.gateway.routes[0].filters[0].args.fallbackUri", () -> "forward:/fallback/pedidos-api");
        registro.add("spring.cloud.gateway.routes[1].id", () -> "cliente-api");
        registro.add("spring.cloud.gateway.routes[1].uri", () -> "http://localhost:" + PUERTO_CERRADO);
        registro.add("spring.cloud.gateway.routes[1].predicates[0]", () -> "Path=/api/clientes/**");
        registro.add("spring.cloud.gateway.routes[1].filters[0].name", () -> "CircuitBreaker");
        registro.add("spring.cloud.gateway.routes[1].filters[0].args.name", () -> "clientesApi");
        registro.add("spring.cloud.gateway.routes[1].filters[0].args.fallbackUri", () -> "forward:/fallback/pedidos-cliente");
        registro.add("eureka.client.enabled", () -> "false");
        // Ventana pequena para abrir el circuito en pocas llamadas, y un timeout de
        // respuesta corto para probar el 504 sin esperar 15 s.
        registro.add("resilience4j.circuitbreaker.configs.microservicio.sliding-window-size", () -> "4");
        registro.add("resilience4j.circuitbreaker.configs.microservicio.minimum-number-of-calls", () -> "4");
        registro.add("resilience4j.circuitbreaker.configs.microservicio.wait-duration-in-open-state", () -> "1m");
        registro.add("spring.cloud.gateway.httpclient.response-timeout", () -> "3s");
    }

    @AfterAll
    static void parar() {
        PEDIDOS.stop();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CircuitBreakerRegistry circuitos;

    @BeforeEach
    void preparar() {
        PEDIDOS.resetAll();
        circuitos.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private static String token() {
        return "Bearer " + TokenDePrueba.token("pedidos.read", "clientes.read");
    }

    @Test
    @DisplayName("microservicio caido: 503 ProblemDetail con Retry-After, no un error de conexion")
    void microservicioCaido() {
        webTestClient.get().uri("/api/clientes/1").header("Authorization", token())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "15")
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.example/problemas/microservicio-no-disponible")
                .jsonPath("$.servicio").isEqualTo("pedidos-cliente");
    }

    @Test
    @DisplayName("una escritura (POST) a un microservicio caido tambien recibe el 503 ProblemDetail")
    void escrituraAMicroservicioCaido() {
        webTestClient.post().uri("/api/clientes").header("Authorization", "Bearer " + TokenDePrueba.token("clientes.write"))
                .header("Content-Type", "application/json")
                .bodyValue("{\"nombre\":\"X\",\"email\":\"x@example.com\"}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.example/problemas/microservicio-no-disponible");
    }

    @Test
    @DisplayName("tras varios fallos el circuito se abre; el de la otra ruta sigue cerrado y enrutando")
    void elCircuitoSeAbreSoloParaSuRuta() {
        for (int i = 0; i < 4; i++) {
            webTestClient.get().uri("/api/clientes/1").header("Authorization", token())
                    .exchange().expectStatus().isEqualTo(503);
        }

        assertThat(circuitos.circuitBreaker("clientesApi").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        webTestClient.get().uri("/api/clientes/1").header("Authorization", token())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.detail").value(detalle ->
                        assertThat((String) detalle).contains("circuito abierto"));

        PEDIDOS.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(okJson("{\"id\":1}")));
        webTestClient.get().uri("/api/pedidos/1").header("Authorization", token())
                .exchange().expectStatus().isOk();
        assertThat(circuitos.circuitBreaker("pedidosApi").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("con el circuito abierto la peticion no llega al microservicio")
    void circuitoAbiertoNoLlama() {
        PEDIDOS.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(okJson("{\"id\":1}")));
        circuitos.circuitBreaker("pedidosApi").transitionToOpenState();

        webTestClient.get().uri("/api/pedidos/1").header("Authorization", token())
                .exchange().expectStatus().isEqualTo(503);

        PEDIDOS.verify(exactly(0), getRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("microservicio lento: 504 ProblemDetail")
    void microservicioLento() {
        PEDIDOS.stubFor(get(urlMatching("/api/pedidos/.*"))
                .willReturn(okJson("{\"id\":1}").withFixedDelay(5000)));

        webTestClient.get().uri("/api/pedidos/1").header("Authorization", token())
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.example/problemas/microservicio-sin-respuesta");
    }

    @Test
    @DisplayName("una respuesta lenta pero dentro del plazo (1,5 s) llega: el TimeLimiter no corta al segundo")
    void respuestaLentaDentroDelPlazo() {
        PEDIDOS.stubFor(get(urlMatching("/api/pedidos/.*"))
                .willReturn(okJson("{\"id\":1}").withFixedDelay(1500)));

        webTestClient.get().uri("/api/pedidos/1").header("Authorization", token())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(1);
    }

    @Test
    @DisplayName("un 5xx del microservicio pasa tal cual y no cuenta como fallo del circuito")
    void cincoXXNoAbreElCircuito() {
        PEDIDOS.stubFor(get(urlMatching("/api/pedidos/.*")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"https://bdo.example/problemas/proveedor-no-disponible\"}")));

        for (int i = 0; i < 5; i++) {
            webTestClient.get().uri("/api/pedidos/1").header("Authorization", token())
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.type")
                    .isEqualTo("https://bdo.example/problemas/proveedor-no-disponible");
        }

        CircuitBreaker circuito = circuitos.circuitBreaker("pedidosApi");
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();
        PEDIDOS.verify(exactly(5), getRequestedFor(urlMatching("/api/pedidos/.*")));
    }

    private static int puertoLibre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
