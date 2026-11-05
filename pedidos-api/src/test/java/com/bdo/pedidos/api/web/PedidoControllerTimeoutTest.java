package com.bdo.pedidos.api.web;

import com.bdo.pedidos.dominio.modelo.Cliente;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Escenario de timeout del proveedor, con su propio presupuesto de tiempo.
 *
 * <p>Esta comprobacion vive en su propia clase por una razon concreta: necesita un
 * timeout por intento CORTO (1 s) para que el test sea rapido, mientras que el resto de
 * los tests de integracion usan un presupuesto holgado (5 s) que no falle por saturacion
 * de la maquina cuando el build compila los modulos en paralelo. Con
 * {@code @DynamicPropertySource} la propiedad sobrescribe la del fichero de test.
 *
 * <p>Comprobado: el timeout se agota, la API responde 504 y el pedido vuelve a
 * {@code PENDIENTE} (reintentable), sin dejar el pedido a medias.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API de pedidos - timeout del proveedor")
class PedidoControllerTimeoutTest {

    /** Timeout por intento del escenario: corto, para que el test no tarde. */
    private static final int TIMEOUT_POR_INTENTO_MS = 1_000;
    /** Latencia del proveedor: tres veces el timeout, siempre por encima. */
    private static final int LATENCIA_PROVEEDOR_MS = 3_000;

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
        registro.add("app.proveedor.timeout-por-intento", () -> TIMEOUT_POR_INTENTO_MS + "ms");
        registro.add("app.proveedor.timeout-global", () -> "10s");
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ClientePort clientes;

    /** Decoder real de los tests (HS256), igual que en el resto de la suite. */
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

    private WebTestClient autorizado(String... permisos) {
        return webTestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + TokenDePrueba.token(permisos))
                .build();
    }

    @Test
    @DisplayName("si el proveedor tarda de mas, el pedido vuelve a PENDIENTE y se responde 504")
    void timeoutDelProveedor() {
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(LATENCIA_PROVEEDOR_MS)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));

        Long id = crearPedido();

        autorizado("pedidos.write")
                .post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange()
                .expectStatus().isEqualTo(504);

        autorizado("pedidos.read").get().uri("/api/pedidos/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("PENDIENTE")
                .jsonPath("$.importeTotal").isEmpty()
                .jsonPath("$.ultimoError").exists();
    }

    private Long crearPedido() {
        String cuerpo = """
                {
                  "clienteId": 1,
                  "codigoPostalDestino": "43840",
                  "lineas": [
                    {"producto": "Teclado", "cantidad": 2, "precioUnitario": 45.9900, "pesoUnitarioKg": 0.800}
                  ]
                }
                """;
        var pedido = autorizado("pedidos.write")
                .post().uri("/api/pedidos")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(com.bdo.pedidos.api.web.dto.PedidoResponse.class)
                .returnResult().getResponseBody();
        assert pedido != null && pedido.id() != null;
        return pedido.id();
    }
}
