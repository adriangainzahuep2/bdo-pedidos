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
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.codec.CodecException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptador <b>reactivo</b> (por defecto) del proveedor logistico.
 *
 * <h2>Cadena de resiliencia (de fuera hacia dentro)</h2>
 * <pre>
 *   Bulkhead        -> rechaza de inmediato si no hay plaza (fallo rapido, sin encolar)
 *   RateLimiter     -> limita el caudal de peticiones al proveedor
 *   CircuitBreaker  -> cuenta fallos del proveedor y deja de llamar si esta caido
 *   timeout global  -> presupuesto total de la operacion (incluye esperas entre reintentos)
 *   reintentos      -> solo ante fallos transitorios (503/429 y error de conexion)
 *   timeout intento -> limite de cada intento individual
 *   HTTP            -> WebClient (Netty, no bloqueante)
 * </pre>
 *
 * <h2>Decisiones</h2>
 * <ul>
 *   <li><b>Cada intento traduce su fallo a un error de dominio</b> (ver
 *       {@link ClasificadorFallosProveedor}). Los reintentos y el circuito deciden sobre
 *       errores de dominio, no sobre excepciones de Netty o de Reactor.</li>
 *   <li><b>El circuito envuelve a los reintentos</b>, no al reves: cuenta <i>un</i> fallo
 *       por peticion de negocio, no uno por cada intento.</li>
 *   <li><b>Los timeouts no se reintentan</b> (el proveedor puede tardar 5 s
 *       legitimamente).</li>
 *   <li>Se respeta {@code Retry-After}, salvo que la espera no quepa en el presupuesto
 *       global: en ese caso se devuelve el error del proveedor en lugar de esperar para
 *       acabar en un timeout.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.proveedor.modo", havingValue = ProveedorLogisticoProperties.MODO_REACTIVO,
        matchIfMissing = true)
public class ProveedorTarifasClientReactivo implements ProveedorTarifasPort {

    private static final Logger log = LoggerFactory.getLogger(ProveedorTarifasClientReactivo.class);

    private final WebClient webClient;
    private final ProveedorLogisticoProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final Clock clock;
    private final PoliticaEsperaReintentos esperas;

    public ProveedorTarifasClientReactivo(@Qualifier("webClientProveedor") WebClient webClient,
                                           ProveedorLogisticoProperties properties,
                                           @Qualifier("circuitBreakerProveedor") CircuitBreaker circuitBreaker,
                                           @Qualifier("rateLimiterProveedor") RateLimiter rateLimiter,
                                           @Qualifier("bulkheadProveedor") Bulkhead bulkhead,
                                           Clock clock) {
        this.webClient = webClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.clock = clock;
        this.esperas = new PoliticaEsperaReintentos(properties);
    }

    @Override
    public Mono<ResultadoTarifa> calcularTarifa(SolicitudTarifa solicitud) {
        return Mono.defer(() -> {
            AtomicInteger intentos = new AtomicInteger();
            long inicio = System.nanoTime();
            Duration presupuesto = properties.getTimeoutGlobal();

            Mono<Tarifa> intento = Mono.defer(() -> {
                        intentos.incrementAndGet();
                        return webClient.post()
                                .uri(properties.getUrl())
                                .contentType(MediaType.APPLICATION_JSON)
                                .headers(cabeceras -> cabeceras.setBearerAuth(properties.getToken()))
                                .bodyValue(solicitud)
                                .exchangeToMono(this::traducirRespuesta);
                    })
                    .timeout(properties.getTimeoutPorIntento())
                    .onErrorMap(this::traducirErrorDeIntento);

            Mono<Tarifa> conReintentos = intento
                    .retryWhen(politicaDeReintentos(inicio, presupuesto))
                    .timeout(presupuesto)
                    .onErrorMap(TimeoutException.class, error -> new ProveedorTimeoutException(
                            "Se ha agotado el tiempo disponible con el proveedor logistico ("
                                    + presupuesto.toMillis() + " ms)", presupuesto.toMillis(), 0));

            return conReintentos
                    .transform(CircuitBreakerOperator.of(circuitBreaker))
                    .transform(RateLimiterOperator.of(rateLimiter))
                    .transform(BulkheadOperator.of(bulkhead))
                    .map(tarifa -> new ResultadoTarifa(tarifa, intentos.get(), duracionMs(inicio)))
                    .doOnSuccess(resultado -> log.info("Tarifa obtenida del proveedor logistico: {} en {} ms ({} intento/s)",
                            resultado.tarifa().coste(), resultado.duracionMs(), resultado.intentosProveedor()))
                    .onErrorMap(error -> ClasificadorFallosProveedor.conIntentos(
                            ClasificadorFallosProveedor.traducirRechazo(error), intentos.get()));
        });
    }

    /** Traduce la respuesta HTTP del proveedor al modelo de dominio. */
    private Mono<Tarifa> traducirRespuesta(ClientResponse respuesta) {
        if (respuesta.statusCode().is2xxSuccessful()) {
            return respuesta.bodyToMono(TarifaRespuesta.class)
                    .map(ProveedorTarifasMapper::aTarifa)
                    // Un 200 sin cuerpo no es una tarifa: sin esta guarda el flujo terminaria
                    // vacio y el pedido se quedaria en CONFIRMANDO.
                    .switchIfEmpty(Mono.error(() -> new ProveedorRespuestaInvalidaException(
                            "El proveedor logistico devolvio una respuesta vacia")));
        }
        return respuesta.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(cuerpo -> Mono.error(new ProveedorHttpException(
                        respuesta.statusCode().value(),
                        ProveedorTarifasMapper.retryAfter(respuesta.headers().asHttpHeaders(), clock),
                        ProveedorTarifasMapper.truncar(cuerpo, 500),
                        0,
                        "El proveedor logistico respondio con HTTP " + respuesta.statusCode().value())));
    }

    /** Traduce el fallo tecnico de un intento a un error de dominio. */
    private Throwable traducirErrorDeIntento(Throwable error) {
        if (error instanceof ErrorDominio) {
            return error;
        }
        long timeoutMs = properties.getTimeoutPorIntento().toMillis();
        if (error instanceof TimeoutException || causaEsTimeoutDeLectura(error)) {
            return new ProveedorTimeoutException(
                    "El proveedor logistico no ha respondido en " + timeoutMs + " ms", timeoutMs, 0);
        }
        if (error instanceof WebClientRequestException) {
            return new ProveedorNoDisponibleException(ClasificadorFallosProveedor.CAUSA_ERROR_CONEXION,
                    "No se pudo conectar con el proveedor logistico: " + error.getMessage(), 0);
        }
        if (error instanceof UnsupportedMediaTypeException || error instanceof CodecException) {
            // JSON mal formado, tipos incompatibles o un cuerpo que no es JSON: es un fallo
            // del proveedor (502), no un error interno del servicio (500).
            return new ProveedorRespuestaInvalidaException(
                    "El proveedor logistico ha devuelto una respuesta que no se puede interpretar: " + error.getMessage());
        }
        return error;
    }

    /** El timeout de respuesta de Netty llega envuelto en un {@link WebClientRequestException}. */
    private static boolean causaEsTimeoutDeLectura(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof ReadTimeoutException) {
                return true;
            }
            if (causa.getCause() == causa) {
                return false;
            }
        }
        return false;
    }

    /**
     * Politica de reintentos (API {@code Retry.from} de Reactor): solo ante fallos
     * transitorios, con espera exponencial y jitter o la indicada por {@code Retry-After}, y
     * nunca mas alla del presupuesto global ni del numero maximo de intentos.
     */
    private Retry politicaDeReintentos(long inicioNanos, Duration presupuesto) {
        return Retry.from(senales -> senales.concatMap(senal -> {
            Throwable causa = senal.failure();
            long reintento = senal.totalRetries() + 1;
            if (!ClasificadorFallosProveedor.esReintentable(causa) || reintento >= properties.getMaxIntentos()) {
                return Mono.error(causa);
            }
            Duration espera = esperas.esperaAntesDe(causa, reintento);
            Duration restante = presupuesto.minusNanos(System.nanoTime() - inicioNanos);
            if (espera.compareTo(restante) >= 0) {
                log.warn("No se reintenta contra el proveedor logistico: la espera ({} ms) no cabe en el presupuesto restante ({} ms)",
                        espera.toMillis(), Math.max(0, restante.toMillis()));
                return Mono.error(causa);
            }
            log.warn("Reintento {} de {} contra el proveedor logistico en {} ms (causa: {})",
                    reintento, properties.getMaxIntentos() - 1, espera.toMillis(), causa.getMessage());
            return Mono.delay(espera).thenReturn(senal);
        }));
    }

    private static long duracionMs(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000L;
    }
}
