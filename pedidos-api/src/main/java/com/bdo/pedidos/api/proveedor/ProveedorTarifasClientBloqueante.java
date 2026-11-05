package com.bdo.pedidos.api.proveedor;

import com.bdo.pedidos.api.config.ProveedorLogisticoProperties;
import com.bdo.pedidos.dominio.error.ErrorDominio;
import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException;
import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.error.ProveedorTimeoutException;
import com.bdo.pedidos.dominio.modelo.ResultadoTarifa;
import com.bdo.pedidos.dominio.modelo.SolicitudTarifa;
import com.bdo.pedidos.dominio.modelo.Tarifa;
import com.bdo.pedidos.dominio.puerto.ProveedorTarifasPort;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Adaptador <b>bloqueante</b> (RestTemplate) del proveedor logistico.
 *
 * <p>Se activa con {@code app.proveedor.modo=bloqueante}. Existe para comparar las dos
 * aproximaciones sobre el mismo puerto y la misma bateria de pruebas, y como camino natural
 * para un equipo con un stack bloqueante (Spring MVC + JPA).
 *
 * <p><b>Por que no se usa por defecto:</b> RestTemplate bloquea el hilo. Si se ejecutara en
 * el event loop de Netty (WebFlux) lo bloquearia; por eso la llamada se delega en
 * {@link Schedulers#boundedElastic()}, el pool dedicado a E/S bloqueante, y se protege con
 * el mismo presupuesto global que el adaptador reactivo.
 *
 * <p>El decorado de Resilience4j se construye de forma <b>programatica</b> para fijar el
 * orden de forma explicita: bulkhead -&gt; limitador -&gt; circuito -&gt; reintentos -&gt;
 * llamada. Las reglas de reintento y de circuito son las mismas que en el adaptador reactivo
 * ({@link ClasificadorFallosProveedor}).
 */
@Component
@ConditionalOnProperty(name = "app.proveedor.modo", havingValue = ProveedorLogisticoProperties.MODO_BLOQUEANTE)
public class ProveedorTarifasClientBloqueante implements ProveedorTarifasPort {

    private static final Logger log = LoggerFactory.getLogger(ProveedorTarifasClientBloqueante.class);

    private final RestTemplate restTemplate;
    private final ProveedorLogisticoProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final Clock clock;

    public ProveedorTarifasClientBloqueante(@Qualifier("restTemplateProveedor") RestTemplate restTemplate,
                                             ProveedorLogisticoProperties properties,
                                             @Qualifier("circuitBreakerProveedor") CircuitBreaker circuitBreaker,
                                             @Qualifier("rateLimiterProveedor") RateLimiter rateLimiter,
                                             @Qualifier("bulkheadProveedor") Bulkhead bulkhead,
                                             @Qualifier("retryProveedor") Retry retry,
                                             Clock clock) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.retry = retry;
        this.clock = clock;
    }

    /**
     * Politica de reintentos del adaptador bloqueante, derivada de las mismas propiedades
     * que el reactivo: mismo numero de intentos, mismas excepciones reintentables y misma
     * espera (backoff exponencial con jitter o {@code Retry-After}).
     */
    public static RetryConfig politicaDeReintentos(ProveedorLogisticoProperties properties) {
        PoliticaEsperaReintentos esperas = new PoliticaEsperaReintentos(properties);
        IntervalBiFunction<Object> espera = (reintento, resultado) -> resultado.isLeft()
                ? esperas.esperaAntesDe(resultado.getLeft(), reintento).toMillis()
                : properties.getBackoffInicial().toMillis();
        return RetryConfig.custom()
                .maxAttempts(properties.getMaxIntentos())
                .retryOnException(ClasificadorFallosProveedor::esReintentable)
                .intervalBiFunction(espera)
                .build();
    }

    @Override
    public Mono<ResultadoTarifa> calcularTarifa(SolicitudTarifa solicitud) {
        return Mono.defer(() -> {
            AtomicInteger intentos = new AtomicInteger();
            long inicio = System.nanoTime();

            Supplier<Tarifa> llamada = () -> {
                intentos.incrementAndGet();
                return llamadaHttp(solicitud);
            };
            Supplier<Tarifa> conReintentos = Retry.decorateSupplier(retry, llamada);
            Supplier<Tarifa> conCircuito = CircuitBreaker.decorateSupplier(circuitBreaker, conReintentos);
            Supplier<Tarifa> conLimite = RateLimiter.decorateSupplier(rateLimiter, conCircuito);
            Supplier<Tarifa> conBulkhead = Bulkhead.decorateSupplier(bulkhead, conLimite);

            return Mono.fromCallable(conBulkhead::get)
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(properties.getTimeoutGlobal())
                    .map(tarifa -> new ResultadoTarifa(tarifa, intentos.get(), (System.nanoTime() - inicio) / 1_000_000L))
                    .doOnSuccess(resultado -> log.info("Tarifa obtenida del proveedor logistico: {} en {} ms ({} intento/s)",
                            resultado.tarifa().coste(), resultado.duracionMs(), resultado.intentosProveedor()))
                    .onErrorMap(error -> ClasificadorFallosProveedor.conIntentos(traducirError(error), intentos.get()));
        });
    }

    /** Un intento: la llamada HTTP con su fallo ya traducido a un error de dominio. */
    private Tarifa llamadaHttp(SolicitudTarifa solicitud) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(properties.getToken());

        try {
            ResponseEntity<TarifaRespuesta> respuesta = restTemplate.exchange(
                    properties.getUrl(), HttpMethod.POST, new HttpEntity<>(solicitud, cabeceras), TarifaRespuesta.class);
            return ProveedorTarifasMapper.aTarifa(respuesta.getBody());
        } catch (RestClientResponseException error) {
            throw new ProveedorHttpException(
                    error.getStatusCode().value(),
                    ProveedorTarifasMapper.retryAfter(error.getResponseHeaders(), clock),
                    ProveedorTarifasMapper.truncar(error.getResponseBodyAsString(), 500),
                    0,
                    "El proveedor logistico respondio con HTTP " + error.getStatusCode().value());
        } catch (ResourceAccessException error) {
            if (error.getCause() instanceof SocketTimeoutException) {
                // Timeout de lectura: el proveedor no ha respondido a tiempo (no se reintenta).
                long timeoutMs = properties.getTimeoutPorIntento().toMillis();
                throw new ProveedorTimeoutException(
                        "El proveedor logistico no ha respondido en " + timeoutMs + " ms", timeoutMs, 0);
            }
            throw new ProveedorNoDisponibleException(ClasificadorFallosProveedor.CAUSA_ERROR_CONEXION,
                    "No se pudo conectar con el proveedor logistico: " + error.getMessage(), 0);
        } catch (RestClientException error) {
            // El cuerpo no se puede convertir (JSON mal formado, tipo de contenido inesperado).
            throw new ProveedorRespuestaInvalidaException(
                    "El proveedor logistico ha devuelto una respuesta que no se puede interpretar: " + error.getMessage());
        }
    }

    private Throwable traducirError(Throwable error) {
        if (error instanceof ErrorDominio) {
            return error;
        }
        if (error instanceof TimeoutException) {
            return new ProveedorTimeoutException(
                    "Se ha agotado el tiempo disponible con el proveedor logistico ("
                            + properties.getTimeoutGlobal().toMillis() + " ms)",
                    properties.getTimeoutGlobal().toMillis(), 0);
        }
        return ClasificadorFallosProveedor.traducirRechazo(error);
    }
}
