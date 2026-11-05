package com.bdo.pedidos.api.proveedor;

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
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
 * Tests de integracion del adaptador reactivo del proveedor logistico contra un stub
 * WireMock que simula al proveedor externo.
 *
 * <p>Cubren los fallos que describe el enunciado (lentitud, 503 intermitentes, token) y
 * los que aparecen en produccion aunque el enunciado no los nombre: respuestas vacias o
 * mal formadas, errores 4xx, errores de conexion y {@code Retry-After}.
 */
@DisplayName("ProveedorTarifasClientReactivo - integracion contra el proveedor simulado")
class ProveedorTarifasClientReactivoTest {

    /**
     * Presupuesto por intento en los escenarios que deben terminar bien: holgado (5 s)
     * para que el test no falle por saturacion de la maquina cuando el build ejecuta los
     * modulos en paralelo. Los escenarios de timeout usan su propio presupuesto corto.
     */
    private static final Duration TIMEOUT_POR_INTENTO_AMPLIO = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_POR_INTENTO_CORTO = Duration.ofSeconds(1);
    private static final String TARIFA_OK = "{\"coste\":7.95,\"moneda\":\"EUR\",\"plazoDias\":2}";

    private WireMockServer proveedor;
    private ProveedorTarifasClientReactivo cliente;

    @BeforeEach
    void preparar() {
        proveedor = new WireMockServer(options().dynamicPort());
        proveedor.start();
        cliente = crearCliente(propiedades(TIMEOUT_POR_INTENTO_AMPLIO), CircuitBreaker.ofDefaults("prueba"));
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
        props.setBackoffMultiplicador(2.0d);
        props.setBackoffJitter(0.1d);
        return props;
    }

    /** Adaptador con el WebClient de produccion (mismos timeouts de Netty). */
    private ProveedorTarifasClientReactivo crearCliente(ProveedorLogisticoProperties props, CircuitBreaker circuito) {
        return new ProveedorTarifasClientReactivo(
                new ProveedorLogisticoConfig().webClientProveedor(props),
                props, circuito,
                RateLimiter.ofDefaults("prueba"),
                Bulkhead.ofDefaults("prueba"),
                Clock.systemUTC());
    }

    /** Circuito con el predicado de produccion y una ventana minima de 2 llamadas. */
    private static CircuitBreaker circuitoDeProduccion() {
        return CircuitBreaker.of("prueba", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(new ClasificadorFallosProveedor.CuentaParaElCircuito())
                .build());
    }

    private static SolicitudTarifa solicitud() {
        return new SolicitudTarifa("43840", new BigDecimal("3.500"), 2);
    }

    @Test
    @DisplayName("devuelve la tarifa y respeta el contrato de la peticion (ruta, cabeceras y cuerpo)")
    void devuelveLaTarifa() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson(TARIFA_OK)));

        ResultadoTarifa resultado = cliente.calcularTarifa(solicitud()).block();

        assertThat(resultado).isNotNull();
        assertThat(resultado.tarifa().coste().importe().toPlainString()).isEqualTo("7.95");
        assertThat(resultado.tarifa().coste().moneda()).isEqualTo("EUR");
        assertThat(resultado.tarifa().plazoDias()).isEqualTo(2);
        assertThat(resultado.intentosProveedor()).isEqualTo(1);

        proveedor.verify(postRequestedFor(urlEqualTo("/api/tarifas"))
                .withHeader("Authorization", equalTo("Bearer token-secreto-del-entorno"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"codigoPostalDestino\":\"43840\",\"pesoKg\":3.500,\"numBultos\":2}")));
    }

    @Test
    @DisplayName("ante un 503 reintenta y, si se agota, propaga el error del proveedor con los intentos realizados")
    void reintentaAnte503() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(serviceUnavailable().withBody("{\"error\":\"servicio no disponible\"}")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorHttpException.class)
                .hasMessageContaining("503")
                .extracting(error -> ((ProveedorHttpException) error).intentos())
                .isEqualTo(3);

        // 1 intento inicial + 2 reintentos.
        proveedor.verify(exactly(3), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("si el proveedor se recupera tras un 503, la peticion termina bien")
    void reintentaYSeRecupera() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .inScenario("recuperacion").whenScenarioStateIs("Started")
                .willReturn(serviceUnavailable())
                .willSetStateTo("recuperado"));
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .inScenario("recuperacion").whenScenarioStateIs("recuperado")
                .willReturn(okJson(TARIFA_OK)));

        ResultadoTarifa resultado = cliente.calcularTarifa(solicitud()).block();

        assertThat(resultado).isNotNull();
        assertThat(resultado.tarifa().coste().importe().toPlainString()).isEqualTo("7.95");
        assertThat(resultado.intentosProveedor()).isEqualTo(2);
        proveedor.verify(exactly(2), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("un 400 es un error de nuestra peticion: no se reintenta")
    void noReintentaUn400() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(badRequest().withBody("{\"error\":\"cp\"}")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorHttpException.class)
                .extracting(error -> ((ProveedorHttpException) error).status())
                .isEqualTo(400);

        proveedor.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("si el proveedor tarda de mas, se agota el tiempo y NO se reintenta")
    void timeoutNoSeReintenta() {
        // Presupuesto corto (1 s) y latencia tres veces mayor: el timeout se dispara siempre.
        cliente = crearCliente(propiedades(TIMEOUT_POR_INTENTO_CORTO), CircuitBreaker.ofDefaults("prueba"));
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson(TARIFA_OK).withFixedDelay(3000)));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorTimeoutException.class)
                .extracting(error -> ((ProveedorTimeoutException) error).intentos())
                .isEqualTo(1);

        proveedor.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("el timeout de respuesta de Netty tambien es un timeout (504), no un error de conexion, y no se reintenta")
    void timeoutDeNettyEsTimeout() {
        ProveedorLogisticoProperties props = propiedades(TIMEOUT_POR_INTENTO_AMPLIO);
        WebClient conTimeoutDeNettyCorto = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofMillis(500))))
                .build();
        cliente = new ProveedorTarifasClientReactivo(conTimeoutDeNettyCorto, props,
                CircuitBreaker.ofDefaults("prueba"), RateLimiter.ofDefaults("prueba"),
                Bulkhead.ofDefaults("prueba"), Clock.systemUTC());
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson(TARIFA_OK).withFixedDelay(3000)));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorTimeoutException.class);

        proveedor.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("si no se puede conectar, reintenta y responde 'no disponible' (ERROR_CONEXION)")
    void errorDeConexion() throws Exception {
        int puertoCerrado;
        try (ServerSocket socket = new ServerSocket(0)) {
            puertoCerrado = socket.getLocalPort();
        }
        ProveedorLogisticoProperties props = propiedades(TIMEOUT_POR_INTENTO_AMPLIO);
        props.setUrl("http://localhost:" + puertoCerrado + "/api/tarifas");
        cliente = crearCliente(props, CircuitBreaker.ofDefaults("prueba"));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorNoDisponibleException.class)
                .satisfies(error -> {
                    ProveedorNoDisponibleException noDisponible = (ProveedorNoDisponibleException) error;
                    assertThat(noDisponible.causa()).isEqualTo(ClasificadorFallosProveedor.CAUSA_ERROR_CONEXION);
                    assertThat(noDisponible.intentos()).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("se respeta Retry-After: espera lo indicado antes de reintentar")
    void respetaRetryAfter() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .inScenario("retry-after").whenScenarioStateIs("Started")
                .willReturn(serviceUnavailable().withHeader("Retry-After", "1"))
                .willSetStateTo("disponible"));
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .inScenario("retry-after").whenScenarioStateIs("disponible")
                .willReturn(okJson(TARIFA_OK)));

        long inicio = System.nanoTime();
        ResultadoTarifa resultado = cliente.calcularTarifa(solicitud()).block();
        long transcurridoMs = (System.nanoTime() - inicio) / 1_000_000L;

        assertThat(resultado).isNotNull();
        assertThat(resultado.intentosProveedor()).isEqualTo(2);
        assertThat(transcurridoMs).isGreaterThanOrEqualTo(1_000L);
    }

    @Test
    @DisplayName("si Retry-After no cabe en el presupuesto global, no se espera: se devuelve el 503 enseguida")
    void retryAfterFueraDelPresupuesto() {
        ProveedorLogisticoProperties props = propiedades(Duration.ofSeconds(2));
        props.setTimeoutGlobal(Duration.ofSeconds(3));
        cliente = crearCliente(props, CircuitBreaker.ofDefaults("prueba"));
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(serviceUnavailable().withHeader("Retry-After", "30")));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorHttpException.class)
                .hasMessageContaining("503");
        long transcurridoMs = (System.nanoTime() - inicio) / 1_000_000L;

        assertThat(transcurridoMs).isLessThan(3_000L);
        proveedor.verify(exactly(1), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("una moneda no soportada se rechaza: no se suman importes de distintas monedas")
    void rechazaMonedaNoSoportada() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":7.95,\"moneda\":\"USD\",\"plazoDias\":2}")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorRespuestaInvalidaException.class)
                .hasMessageContaining("moneda");
    }

    @Test
    @DisplayName("una respuesta incompleta se rechaza")
    void rechazaRespuestaIncompleta() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"moneda\":\"EUR\",\"plazoDias\":2}")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorRespuestaInvalidaException.class);
    }

    @Test
    @DisplayName("un 200 sin cuerpo es una respuesta invalida (no un flujo vacio)")
    void rechazaRespuestaVacia() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorRespuestaInvalidaException.class)
                .hasMessageContaining("vacia");
    }

    @Test
    @DisplayName("un JSON mal formado es una respuesta invalida del proveedor (502), no un error interno")
    void rechazaJsonMalFormado() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(okJson("{\"coste\":\"siete\",")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorRespuestaInvalidaException.class);
    }

    @Test
    @DisplayName("un cuerpo que no es JSON es una respuesta invalida del proveedor")
    void rechazaCuerpoQueNoEsJson() {
        proveedor.stubFor(post(urlEqualTo("/api/tarifas"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html>mantenimiento</html>")));

        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorRespuestaInvalidaException.class);
    }

    @Test
    @DisplayName("con el circuito abierto no se hace ninguna llamada al proveedor")
    void circuitoAbiertoNoLlamaAlProveedor() {
        CircuitBreaker circuito = circuitoDeProduccion();
        cliente = crearCliente(propiedades(TIMEOUT_POR_INTENTO_AMPLIO), circuito);
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(serviceUnavailable()));

        // Dos peticiones de negocio fallidas (cada una con sus 3 intentos) abren el circuito.
        for (int peticion = 0; peticion < 2; peticion++) {
            assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                    .isInstanceOf(ProveedorHttpException.class);
        }
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // El circuito cuenta peticiones de negocio, no intentos: 2 llamadas registradas, no 6.
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        // A partir de aqui ya no se llama al proveedor: se responde de inmediato.
        proveedor.resetRequests();
        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorNoDisponibleException.class)
                .hasMessageContaining("circuito")
                .extracting(error -> ((ProveedorNoDisponibleException) error).intentos())
                .isEqualTo(0);

        proveedor.verify(exactly(0), postRequestedFor(urlEqualTo("/api/tarifas")));
    }

    @Test
    @DisplayName("los errores 4xx no abren el circuito; los timeouts si cuentan")
    void queCuentaParaElCircuito() {
        CircuitBreaker circuito = circuitoDeProduccion();
        cliente = crearCliente(propiedades(TIMEOUT_POR_INTENTO_CORTO), circuito);

        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(badRequest()));
        for (int peticion = 0; peticion < 3; peticion++) {
            assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                    .isInstanceOf(ProveedorHttpException.class);
        }
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();

        // Un timeout si es un fallo: con la ventana de 2 llamadas (un 400 "correcto" y este
        // timeout) la tasa de fallos llega al 50 % y el circuito se abre.
        proveedor.stubFor(post(urlEqualTo("/api/tarifas")).willReturn(okJson(TARIFA_OK).withFixedDelay(3000)));
        assertThatThrownBy(() -> cliente.calcularTarifa(solicitud()).block())
                .isInstanceOf(ProveedorTimeoutException.class);
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
