package com.bdo.pedidos.api.config;

import com.bdo.pedidos.api.proveedor.ProveedorTarifasClientBloqueante;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Configuracion del cliente HTTP y de los mecanismos de resiliencia hacia el proveedor.
 *
 * <p>Los parametros del circuito, del limitador y del bulkhead se declaran en
 * {@code application.yml} bajo {@code resilience4j.*}; aqui se recuperan los beans ya
 * configurados con un nombre de instancia explicito ({@code proveedorLogistico}). Los
 * reintentos <b>no</b> se configuran en el YAML: se derivan de {@code app.proveedor.*} para
 * que los dos adaptadores (reactivo y bloqueante) apliquen exactamente la misma politica.
 */
@Configuration(proxyBeanMethods = false)
public class ProveedorLogisticoConfig {

    /** Nombre de la instancia de Resilience4j dedicada al proveedor logistico. */
    public static final String INSTANCIA = "proveedorLogistico";

    /**
     * Margen del timeout de respuesta de Netty sobre el timeout por intento. El timeout que
     * manda es el del flujo reactivo ({@code timeout-por-intento}); el de Netty es solo la
     * ultima linea de defensa para que una conexion nunca quede colgada.
     */
    private static final Duration MARGEN_TIMEOUT_NETTY = Duration.ofSeconds(1);

    /** Cliente HTTP reactivo con los timeouts de conexion y de respuesta del proveedor. */
    @Bean
    public WebClient webClientProveedor(ProveedorLogisticoProperties properties,
                                        ObservationRegistry observaciones) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getTimeoutPorIntento().plus(MARGEN_TIMEOUT_NETTY));

        return WebClient.builder()
                // Span de la llamada al proveedor: en Zipkin se ve cuanto tarda cada intento.
                .observationRegistry(observaciones)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Circuit breaker: deja de llamar al proveedor cuando falla de forma sostenida, para no
     * empeorar una incidencia y para poder responder rapido al cliente. Que cuenta como
     * fallo lo decide {@code ClasificadorFallosProveedor.CuentaParaElCircuito}.
     */
    @Bean
    public CircuitBreaker circuitBreakerProveedor(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(INSTANCIA);
    }

    /** Limitador de peticiones: protege al proveedor de nosotros. */
    @Bean
    public RateLimiter rateLimiterProveedor(RateLimiterRegistry registry) {
        return registry.rateLimiter(INSTANCIA);
    }

    /** Bulkhead: limita el numero de llamadas concurrentes al proveedor. */
    @Bean
    public Bulkhead bulkheadProveedor(BulkheadRegistry registry) {
        return registry.bulkhead(INSTANCIA);
    }

    /**
     * Reintentos del adaptador bloqueante (el reactivo usa el operador de Reactor con la
     * misma politica). Se registra en el {@link RetryRegistry} para exponer sus metricas.
     */
    @Bean
    @ConditionalOnProperty(name = "app.proveedor.modo", havingValue = ProveedorLogisticoProperties.MODO_BLOQUEANTE)
    public Retry retryProveedor(RetryRegistry registry, ProveedorLogisticoProperties properties) {
        return registry.retry(INSTANCIA, ProveedorTarifasClientBloqueante.politicaDeReintentos(properties));
    }

    /** Cliente HTTP bloqueante, con los mismos timeouts que el reactivo. */
    @Bean
    @ConditionalOnProperty(name = "app.proveedor.modo", havingValue = ProveedorLogisticoProperties.MODO_BLOQUEANTE)
    public RestTemplate restTemplateProveedor(ProveedorLogisticoProperties properties,
                                              ObservationRegistry observaciones) {
        SimpleClientHttpRequestFactory factoria = new SimpleClientHttpRequestFactory();
        factoria.setConnectTimeout(properties.getConnectTimeout());
        factoria.setReadTimeout(properties.getTimeoutPorIntento());
        RestTemplate restTemplate = new RestTemplate(factoria);
        restTemplate.setObservationRegistry(observaciones);
        return restTemplate;
    }
}
