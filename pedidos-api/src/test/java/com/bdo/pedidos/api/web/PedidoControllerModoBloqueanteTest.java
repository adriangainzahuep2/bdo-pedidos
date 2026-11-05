package com.bdo.pedidos.api.web;

import com.bdo.pedidos.api.proveedor.ProveedorTarifasClientBloqueante;
import com.bdo.pedidos.dominio.modelo.Cliente;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import com.bdo.pedidos.dominio.puerto.ProveedorTarifasPort;
import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

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
 * La aplicacion completa con {@code app.proveedor.modo=bloqueante}: comprueba que el
 * adaptador RestTemplate se cablea (beans condicionales, cualificadores, reintentos) y que
 * el flujo de confirmacion funciona igual que con el adaptador reactivo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API de pedidos - adaptador bloqueante (RestTemplate)")
class PedidoControllerModoBloqueanteTest {

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
        registro.add("app.proveedor.modo", () -> "bloqueante");
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
    private ProveedorTarifasPort proveedor;

    @MockBean
    private ClientePort clientes;

    private WebTestClient autorizado() {
        return webTestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + TokenDePrueba.token("pedidos.write", "pedidos.read"))
                .build();
    }

    private long crearPedido() {
        when(clientes.buscarPorId(anyLong())).thenReturn(Mono.just(new Cliente(1L, "Ana Lopez", "ana@example.com")));
        var pedido = autorizado().post().uri("/api/pedidos")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"clienteId\":1,\"codigoPostalDestino\":\"43840\",\"lineas\":["
                        + "{\"producto\":\"Teclado\",\"cantidad\":2,\"precioUnitario\":45.99,\"pesoUnitarioKg\":0.8}]}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(com.bdo.pedidos.api.web.dto.PedidoResponse.class)
                .returnResult().getResponseBody();
        assertThat(pedido).isNotNull();
        return pedido.id();
    }

    @Test
    @DisplayName("usa el adaptador bloqueante y confirma el pedido")
    void confirmaConElAdaptadorBloqueante() {
        assertThat(proveedor).isInstanceOf(ProveedorTarifasClientBloqueante.class);
        PROVEEDOR.resetAll();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}")));
        long id = crearPedido();

        autorizado().post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("CONFIRMADO")
                .jsonPath("$.importeTotal").isEqualTo(99.93);
    }

    @Test
    @DisplayName("ante 503 reintenta 3 veces, responde 502 y el pedido queda PENDIENTE")
    void falloConElAdaptadorBloqueante() {
        PROVEEDOR.resetAll();
        PROVEEDOR.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(serviceUnavailable()));
        long id = crearPedido();

        autorizado().post().uri("/api/pedidos/{id}/confirmacion", id)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.intentos").isEqualTo(3);

        PROVEEDOR.verify(exactly(3), postRequestedFor(urlEqualTo("/api/tarifas")));
        autorizado().get().uri("/api/pedidos/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("PENDIENTE");
    }
}
