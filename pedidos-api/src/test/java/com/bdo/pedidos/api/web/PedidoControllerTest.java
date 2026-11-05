package com.bdo.pedidos.api.web;

import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorTimeoutException;
import com.bdo.pedidos.dominio.modelo.Cliente;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Test de integracion del flujo completo de la API: crear pedido, confirmar contra el
 * proveedor simulado y verificar el comportamiento en los escenarios de fallo y de
 * concurrencia que describe el enunciado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API de pedidos - flujo completo con proveedor simulado")
class PedidoControllerTest {

    private static final WireMockServer PROVEEDOR = new WireMockServer(options().dynamicPort());

    static {
        PROVEEDOR.start();
    }

    @AfterAll
    static void pararProveedor() {
        PROVEEDOR.stop();
    }

    @DynamicPropertySource
    static void propiedadesProveedor(DynamicPropertyRegistry registro) {
        registro.add("app.proveedor.url", () -> "http://localhost:" + PROVEEDOR.port() + "/api/tarifas");
    }

    @Autowired
    private WebTestClient webTestClient;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int puerto;

    @MockBean
    private ClientePort clientes;

    @Autowired
    private DatabaseClient baseDeDatos;

    @Autowired
    @Qualifier("circuitBreakerProveedor")
    private CircuitBreaker circuitoProveedor;

    /**
     * Decoder real de los tests: valida la firma HMAC, el emisor y la caducidad del token
     * generado por {@link TokenDePrueba}.
     *
     * <p>En WebFlux el Resource Server usa un {@link ReactiveJwtDecoder} (no el
     * {@code JwtDecoder} sincrono de Spring MVC): por eso se declara aqui el tipo reactivo.
     */
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

    @BeforeEach
    void preparar() {
        PROVEEDOR.resetAll();
        when(clientes.buscarPorId(anyLong()))
                .thenReturn(Mono.just(new Cliente(1L, "Ana Lopez", "ana@example.com")));
    }

    /** Cliente con un token JWT real que lleva los permisos indicados. */
    private WebTestClient autorizado(String... permisos) {
        return webTestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + TokenDePrueba.token(permisos))
                .build();
    }

    private static final String PJSON = """
            {
              "clienteId": 1,
              "codigoPostalDestino": "43840",
              "lineas": [
                {"producto": "Teclado", "cantidad": 2, "precioUnitario": 45.9900, "pesoUnitarioKg": 0.800},
                {"producto": "Raton", "cantidad": 1, "precioUnitario": 19.5000, "pesoUnitarioKg": 0.100}
              ]
            }
            """;

    private Long crearPedido() {
        com.bdo.pedidos.api.web.dto.PedidoResponse pedido = autorizado("pedidos.write")
                .post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(PJSON)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(com.bdo.pedidos.api.web.dto.PedidoResponse.class)
                .returnResult().getResponseBody();
        assertThat(pedido).isNotNull();
        assertThat(pedido.id()).isNotNull();
        return pedido.id();
    }

    @Test
    @DisplayName("crea el pedido con sus lineas y calcula el importe en el servidor")
    void creaElPedido() {
        autorizado("pedidos.write")
                .post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(PJSON)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().value("Location", org.hamcrest.Matchers.startsWith("/api/pedidos/"))
                .expectBody()
                .jsonPath("$.estado").isEqualTo("PENDIENTE")
                .jsonPath("$.importeProductos").isEqualTo(111.48)
                .jsonPath("$.importeTotal").isEmpty()
                .jsonPath("$.costeEnvio").isEmpty()
                .jsonPath("$.pesoTotalKg").isEqualTo(1.700)
                .jsonPath("$.numBultos").isEqualTo(3)
                .jsonPath("$.lineas.length()").isEqualTo(2)
                .jsonPath("$.lineas[0].importeLinea").isEqualTo(91.98);
    }

    @Test
    @DisplayName("el importe enviado por el cliente se ignora: el total lo calcula el servidor")
    void ignoraElImporteDelCliente() {
        String peticionConImportes = """
                {
                  "clienteId": 1,
                  "codigoPostalDestino": "43840",
                  "importeTotal": 0.01,
                  "importeProductos": 0.01,
                  "costeEnvio": 0.01,
                  "lineas": [
                    {"producto": "Teclado", "cantidad": 2, "precioUnitario": 45.9900, "pesoUnitarioKg": 0.800}
                  ]
                }
                """;

        autorizado("pedidos.write")
                .post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(peticionConImportes)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.importeProductos").isEqualTo(91.98)
                .jsonPath("$.importeTotal").isEmpty();
    }

    @Test
    @DisplayName("confirma el pedido: guarda el coste de envio, actualiza el total y llama al proveedor una vez")
    void confirmaElPedido() {
        Long id = crearPedido();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));

        autorizado("pedidos.write")
                .post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("CONFIRMADO")
                .jsonPath("$.costeEnvio").isEqualTo(7.95)
                .jsonPath("$.importeTotal").isEqualTo(119.43)
                .jsonPath("$.plazoEnvioDias").isEqualTo(2);

        PROVEEDOR.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas"))
                .withRequestBody(equalToJson(
                        "{\"codigoPostalDestino\":\"43840\",\"pesoKg\":1.700,\"numBultos\":3}")));
    }

    @Test
    @DisplayName("confirmar dos veces es idempotente: la segunda no vuelve a llamar al proveedor")
    void confirmarDosVeces() {
        Long id = crearPedido();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));

        autorizado("pedidos.write").post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange().expectStatus().isOk();
        autorizado("pedidos.write").post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("CONFIRMADO")
                .jsonPath("$.importeTotal").isEqualTo(119.43);

        PROVEEDOR.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("dos confirmaciones simultaneas del mismo pedido: una sola llamada al proveedor")
    void dosConfirmacionesSimultaneas() throws Exception {
        Long id = crearPedido();
        // El proveedor tarda: la segunda peticion llega mientras la primera sigue en vuelo.
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));

        // Dos hilos reales y dos peticiones HTTP simultaneas: es la forma fiel de
        // reproducir dos confirmaciones concurrentes del mismo pedido.
        String token = TokenDePrueba.token("pedidos.write");
        assertThat(confirmarEnParalelo(id, token, 2)).containsExactly(200, 409);

        // La clave: el proveedor solo ha recibido UNA llamada.
        PROVEEDOR.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));

        // Y el pedido queda coherente: confirmado, con su total y sin datos a medias.
        autorizado("pedidos.read").get().uri("/api/pedidos/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("CONFIRMADO")
                .jsonPath("$.costeEnvio").isEqualTo(7.95)
                .jsonPath("$.importeTotal").isEqualTo(119.43);
    }

    @Test
    @DisplayName("diez confirmaciones simultaneas: una 200, nueve 409 y una sola llamada al proveedor")
    void diezConfirmacionesSimultaneas() throws Exception {
        Long id = crearPedido();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}").withFixedDelay(1500)));

        List<Integer> estados = confirmarEnParalelo(id, TokenDePrueba.token("pedidos.write"), 10);

        assertThat(estados).filteredOn(estado -> estado == 200).hasSize(1);
        assertThat(estados).filteredOn(estado -> estado == 409).hasSize(9);
        PROVEEDOR.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
        assertThat(intentosRegistrados(id)).containsExactly("OK");
    }

    /** Lanza N confirmaciones a la vez (hilos reales, peticiones HTTP reales) y devuelve los codigos ordenados. */
    private List<Integer> confirmarEnParalelo(long id, String token, int peticiones) throws Exception {
        ExecutorService ejecutor = Executors.newFixedThreadPool(peticiones);
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < peticiones; i++) {
                futuros.add(ejecutor.submit(() -> confirmarEnHilo(id, token)));
            }
            List<Integer> estados = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                estados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return estados.stream().sorted().toList();
        } finally {
            ejecutor.shutdownNow();
        }
    }

    /** Resultados de la tabla de auditoria de intentos de un pedido, en orden. */
    private List<String> intentosRegistrados(long pedidoId) {
        return baseDeDatos.sql("SELECT resultado FROM pedido_confirmacion_intento WHERE pedido_id = :id ORDER BY id")
                .bind("id", pedidoId)
                .map((fila, meta) -> fila.get("resultado", String.class))
                .all()
                .collectList()
                .block();
    }

    /** Confirmacion desde un hilo propio, con cliente HTTP bloqueante. */
    private int confirmarEnHilo(long id, String token) {
        return org.springframework.web.reactive.function.client.WebClient.builder().build()
                .post()
                .uri("http://localhost:" + puerto + "/api/pedidos/{id}/confirmacion", id)
                .header("Authorization", "Bearer " + token)
                .exchangeToMono(respuesta -> respuesta.releaseBody().thenReturn(respuesta.statusCode().value()))
                .block(java.time.Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("si el proveedor falla con 503, el pedido vuelve a PENDIENTE y se responde 502")
    void falloDelProveedor() {
        Long id = crearPedido();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(serviceUnavailable()));

        autorizado("pedidos.write")
                .post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.detail").exists();

        autorizado("pedidos.read").get().uri("/api/pedidos/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("PENDIENTE")
                .jsonPath("$.importeTotal").isEmpty()
                .jsonPath("$.costeEnvio").isEmpty()
                .jsonPath("$.numeroIntentosConfirmacion").isEqualTo(1)
                .jsonPath("$.ultimoError").exists();

        // El intento queda auditado, y el pedido sigue siendo confirmable.
        assertThat(intentosRegistrados(id)).containsExactly("ERROR");
        PROVEEDOR.resetAll();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));
        autorizado("pedidos.write").post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("CONFIRMADO");
        assertThat(intentosRegistrados(id)).containsExactly("ERROR", "OK");
    }

    @Test
    @DisplayName("si el proveedor responde 200 sin cuerpo, se responde 502 y el pedido vuelve a PENDIENTE")
    void respuestaVaciaDelProveedor() {
        Long id = crearPedido();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));

        autorizado("pedidos.write").post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange().expectStatus().isEqualTo(502);

        autorizado("pedidos.read").get().uri("/api/pedidos/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("el circuito usa el predicado de la configuracion: un 4xx no cuenta como fallo, un 503 o un timeout si")
    void predicadoDelCircuito() {
        var predicado = circuitoProveedor.getCircuitBreakerConfig().getRecordExceptionPredicate();
        assertThat(predicado.test(new ProveedorHttpException(400, null, "", 1, "HTTP 400"))).isFalse();
        assertThat(predicado.test(new ProveedorHttpException(503, null, "", 3, "HTTP 503"))).isTrue();
        assertThat(predicado.test(new ProveedorTimeoutException("lento", 6000, 1))).isTrue();
    }

    // El escenario del timeout vive en PedidoControllerTimeoutTest: necesita su propio
    // presupuesto de tiempo (mas corto) y asi el resto de los tests de esta clase pueden
    // usar un presupuesto holgado, sin depender de la saturacion de la maquina.

    @Test
    @DisplayName("un pedido inexistente devuelve 404")
    void pedidoInexistente() {
        autorizado("pedidos.read").get().uri("/api/pedidos/{id}", 999999)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("una peticion invalida devuelve 400 con el detalle de los errores")
    void peticionInvalida() {
        String peticionInvalida = """
                {
                  "clienteId": 1,
                  "codigoPostalDestino": "43840",
                  "lineas": [
                    {"producto": "Teclado", "cantidad": 0, "precioUnitario": 45.99, "pesoUnitarioKg": 0.8}
                  ]
                }
                """;

        autorizado("pedidos.write")
                .post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(peticionInvalida)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errores").isArray();
    }

    @Test
    @DisplayName("un pedido sin lineas o con bultos a cero es una peticion invalida (400)")
    void pedidoSinLineas() {
        autorizado("pedidos.write").post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"clienteId\":1,\"codigoPostalDestino\":\"43840\",\"lineas\":[]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errores[0].campo").isEqualTo("lineas");

        autorizado("pedidos.write").post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"clienteId\":1,\"codigoPostalDestino\":\"43840\",\"numBultos\":0,\"lineas\":["
                        + "{\"producto\":\"Teclado\",\"cantidad\":1,\"precioUnitario\":1.00,\"pesoUnitarioKg\":0.5}]}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("un JSON mal formado es una peticion invalida (400), no un error interno")
    void jsonMalFormado() {
        autorizado("pedidos.write").post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"clienteId\": 1, \"lineas\": [")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("la consola estatica de demostracion se sirve sin token")
    void consolaEstatica() {
        webTestClient.get().uri("/index.html").exchange().expectStatus().isOk();
        webTestClient.get().uri("/app.js").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("sin token no se puede crear un pedido")
    void sinToken() {
        webTestClient.post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(PJSON)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("un token sin el permiso de escritura no puede crear pedidos")
    void sinPermiso() {
        autorizado("pedidos.read")
                .post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(PJSON)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("un pedido pendiente se puede cancelar y ya no se puede confirmar")
    void cancelar() {
        Long id = crearPedido();

        autorizado("pedidos.write").post().uri("/api/pedidos/{id}/cancelacion", id)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("CANCELADO");

        autorizado("pedidos.write").post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange().expectStatus().isEqualTo(409);
    }
}
