package com.bdo.pedidos.api.proveedor;

import io.micrometer.observation.ObservationRegistry;
import com.bdo.pedidos.api.config.ProveedorLogisticoConfig;
import com.bdo.pedidos.api.config.ProveedorLogisticoProperties;
import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException;
import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.error.ProveedorTimeoutException;
import com.bdo.pedidos.dominio.modelo.ResultadoTarifa;
import com.bdo.pedidos.dominio.modelo.SolicitudTarifa;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del adaptador <b>bloqueante</b> (RestTemplate) contra el mismo stub del proveedor.
 *
 * <p>Se usan los mismos beans de produccion (RestTemplate con sus timeouts y la politica
 * de reintentos derivada de las propiedades): la decision "reactivo o bloqueante" es de
 * infraestructura y el comportamiento observable debe ser el mismo.
 */
@DisplayName("ProveedorTarifasClientBloqueante - integracion contra el proveedor simulado")
class ProveedorTarifasClientBloqueanteTest {

    private static final Duration TIMEOUT_POR_INTENTO_AMPLIO = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_POR_INTENTO_CORTO = Duration.ofSeconds(1);
    private static final String TARIFA_OK = "{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}";

    private WireMockServer proveedor;
    private ProveedorTarifasClientBloqueante cliente;

    @BeforeEach
    void preparar() {
        proveedor = new WireMockServer(options().dynamicPort());
        proveedor.start();
        cliente = crearCliente(propiedades(TIMEOUT_POR_INTENTO_AMPLIO));
    }

    @AfterEach
    void terminar() {
        proveedor.stop();
    }

    private ProveedorLogisticoProperties propiedades(Duration timeoutPorIntento) {
        ProveedorLogisticoProperties props = new ProveedorLogisticoProperties();
        props.setUrl("http://localhost:" + proveedor.port() + "/api/tarifas");
        props.setToken("token-secreto-del-entorno");
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setTimeoutPorIntento(timeoutPorIntento);
        props.setTimeoutGlobal(Duration.ofSeconds(30));
        props.setMaxIntentos(3);
        props.setBackoffInicial(Duration.ofMillis(20));
        return props;
    }

    private ProveedorTarifasClientBloqueante crearCliente(ProveedorLogisticoProperties props) {
        return new ProveedorTarifasClientBloqueante(
                new ProveedorLogisticoConfig().restTemplateProveedor(props, ObservationRegistry.NOOP), props,
                CircuitBreaker.ofDefaults("prueba"),
                RateLimiter.ofDefaults("prueba"),
                Bulkhead.ofDefaults("prueba"),
                Retry.of("prueba", ProveedorTarifasClientBloqueante.politicaDeReintentos(props)),
                Clock.systemUTC());
    }

    private static SolicitudTarifa solicitud() {
        return new SolicitudTarifa("43840", new BigDecimal("3.500"), 2);
    }

    @Test
    @DisplayName("devuelve la tarifa y respeta el contrato de la peticion")
    void devuelveLaTarifa() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson(TARIFA_OK)));

        ResultadoTarifa resultado = cliente.calcularTarifa(solicitud()).block();

        assertThat(resultado).isNotNull();
        assertThat(resultado.tarifa().coste().importe().toPlainString()).isEqualTo("7.95");
        assertThat(resultado.intentosProveedor()).isEqualTo(1);

        proveedor.verify(postRequestedFor(urlEqualTo("/api/tarifas"))
                .withHeader("Authorization", equalTo("Bearer token-secreto-del-entorno"))
                .withRequestBody(equalToJson("{\"codigoPostalDestino\":\"43840\",\"pesoKg\":3.500,\"numBultos\":2}")));
    }

    @Test
    @DisplayName("ante un 503 reintenta hasta agotar los intentos")
    void reintentaAnte503() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorHttpException.class)
                .hasMessageContaining("503")
                .extracting(error -> ((ProveedorHttpException) error).intentos())
                .isEqualTo(3);

        proveedor.verify(exactly(3), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("un 400 no se reintenta (misma politica que el adaptador reactivo)")
    void noReintentaUn400() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(badRequest()));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorHttpException.class)
                .extracting(error -> ((ProveedorHttpException) error).status())
                .isEqualTo(400);

        proveedor.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("si el proveedor tarda de mas, se agota el tiempo y no se reintenta")
    void timeout() {
        cliente = crearCliente(propiedades(TIMEOUT_POR_INTENTO_CORTO));
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson(TARIFA_OK).withFixedDelay(3000)));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorTimeoutException.class);

        proveedor.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("si no se puede conectar, reintenta y responde 'no disponible'")
    void errorDeConexion() throws Exception {
        int puertoCerrado;
        try (ServerSocket socket = new ServerSocket(0)) {
            puertoCerrado = socket.getLocalPort();
        }
        ProveedorLogisticoProperties props = propiedades(TIMEOUT_POR_INTENTO_AMPLIO);
        props.setUrl("http://localhost:" + puertoCerrado + "/api/tarifas");
        cliente = crearCliente(props);

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorNoDisponibleException.class)
                .extracting(error -> ((ProveedorNoDisponibleException) error).intentos())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("un JSON mal formado es una respuesta invalida del proveedor")
    void rechazaJsonMalFormado() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson("{\"coste\":\"siete\",")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorRespuestaInvalidaException.class);
    }
}
