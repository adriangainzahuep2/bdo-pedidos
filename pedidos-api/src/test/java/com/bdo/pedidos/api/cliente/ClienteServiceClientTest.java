package com.bdo.pedidos.api.cliente;

import com.bdo.pedidos.api.config.ClientesProperties;
import com.bdo.pedidos.api.web.FiltroPropagacionToken;
import com.bdo.pedidos.dominio.modelo.Cliente;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Adaptador del servicio de clientes contra un stub WireMock. */
@DisplayName("ClienteServiceClient - integracion con el servicio de clientes simulado")
class ClienteServiceClientTest {

    /**
     * Presupuesto amplio para los escenarios normales.
     *
     * <p>El timeout del cliente es el presupuesto de la llamada, no una asercion: un presupuesto
     * corto en un escenario que debe <b>responder bien</b> hace que el test falle por la lentitud
     * de la maquina (arrancar WireMock y Netty con la CPU saturada) y no por el codigo. El
     * presupuesto corto se reserva para el escenario que <b>quiere</b> agotarlo.
     *
     * <p>Mismo criterio que {@code ProveedorTarifasClientReactivoTest}.
     */
    private static final Duration TIMEOUT_AMPLIO = Duration.ofSeconds(5);

    /** Presupuesto corto: solo para el escenario en el que el timeout debe dispararse. */
    private static final Duration TIMEOUT_CORTO = Duration.ofSeconds(1);

    private WireMockServer servicioClientes;
    private ClienteServiceClient cliente;

    @BeforeEach
    void preparar() {
        servicioClientes = new WireMockServer(options().dynamicPort());
        servicioClientes.start();
        cliente = clienteConTimeout(TIMEOUT_AMPLIO);
    }

    /** Crea el adaptador con el timeout indicado (el de produccion es 2 s). */
    private ClienteServiceClient clienteConTimeout(Duration timeout) {
        ClientesProperties props = new ClientesProperties();
        props.setUrl("http://localhost:" + servicioClientes.port());
        props.setLoadBalanced(false);
        props.setTimeout(timeout);
        return new ClienteServiceClient(WebClient.builder().baseUrl(props.getUrl()).build(), props,
                CircuitBreaker.ofDefaults("prueba"));
    }

    @AfterEach
    void terminar() {
        servicioClientes.stop();
    }

    @Test
    @DisplayName("devuelve el cliente y propaga el token de la peticion de entrada")
    void devuelveElCliente() {
        servicioClientes.stubFor(get(urlEqualTo("/api/clientes/7"))
                .willReturn(okJson("{\"id\":7,\"nombre\":\"Ana Lopez\",\"email\":\"ana@example.com\"}")));

        StepVerifier.create(cliente.buscarPorId(7L)
                        .contextWrite(contexto -> contexto.put(FiltroPropagacionToken.CONTEXTO_TOKEN, "Bearer abc")))
                .assertNext(encontrado -> assertThat(encontrado).isEqualTo(new Cliente(7L, "Ana Lopez", "ana@example.com")))
                .verifyComplete();

        servicioClientes.verify(getRequestedFor(urlEqualTo("/api/clientes/7"))
                .withHeader("Authorization", equalTo("Bearer abc")));
    }

    @Test
    @DisplayName("un 404 (con cuerpo ProblemDetail, como el real) significa 'no existe', no 'no disponible'")
    void clienteInexistente() {
        servicioClientes.stubFor(get(urlEqualTo("/api/clientes/99"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"type\":\"https://bdo.es/clientes/problemas/cliente-no-encontrado\","
                                + "\"title\":\"Not Found\",\"status\":404,\"detail\":\"No existe el cliente con id 99\"}")));

        StepVerifier.create(cliente.buscarPorId(99L)).verifyComplete();
    }

    @Test
    @DisplayName("un 5xx del servicio de clientes es 'no disponible' (503)")
    void servicioCaido() {
        servicioClientes.stubFor(get(urlEqualTo("/api/clientes/7")).willReturn(serverError()));

        StepVerifier.create(cliente.buscarPorId(7L))
                .expectError(ClienteServiceNoDisponibleException.class)
                .verify();
    }

    @Test
    @DisplayName("si el servicio de clientes no responde a tiempo, es 'no disponible'")
    void servicioLento() {
        // Presupuesto corto (1 s) y latencia tres veces mayor: el timeout se dispara siempre.
        ClienteServiceClient clienteConTimeoutCorto = clienteConTimeout(TIMEOUT_CORTO);
        servicioClientes.stubFor(get(urlEqualTo("/api/clientes/7"))
                .willReturn(okJson("{\"id\":7,\"nombre\":\"Ana\",\"email\":\"a@b.es\"}").withFixedDelay(3000)));

        StepVerifier.create(clienteConTimeoutCorto.buscarPorId(7L))
                .expectError(ClienteServiceNoDisponibleException.class)
                .verify(Duration.ofSeconds(5));
    }
}
