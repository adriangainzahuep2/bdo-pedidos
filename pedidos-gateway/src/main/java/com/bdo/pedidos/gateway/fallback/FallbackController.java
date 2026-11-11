package com.bdo.pedidos.gateway.fallback;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

/**
 * Respuesta del gateway cuando el filtro {@code CircuitBreaker} de una ruta no puede
 * entregar la peticion al microservicio.
 *
 * <p>Llega aqui por {@code fallbackUri: forward:/fallback/...} en tres casos: el circuito
 * esta abierto (no se intenta la llamada), el microservicio no responde a la conexion
 * (caido o sin instancias en Eureka), o no responde a tiempo. Los dos primeros son
 * {@code 503}; el ultimo, {@code 504}. Responder en {@code ProblemDetail}, igual que los
 * microservicios, permite al cliente tratar todos los errores con el mismo codigo.
 *
 * <p>Las respuestas 5xx del microservicio NO pasan por aqui: el filtro las deja pasar tal
 * cual (son la forma en que pedidos-api informa de que el proveedor falla).
 *
 * <p>El reenvio conserva el metodo de la peticion original. Se declaran solo los dos que usan
 * las rutas (GET y POST), cada uno con su anotacion: un {@code @RequestMapping} sin metodo
 * aceptaria cualquiera, mezclando metodos seguros y de escritura en el mismo endpoint.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @GetMapping("/fallback/{servicio}")
    public ProblemDetail fallbackLectura(@PathVariable String servicio, ServerWebExchange exchange) {
        return fallback(servicio, exchange);
    }

    @PostMapping("/fallback/{servicio}")
    public ProblemDetail fallbackEscritura(@PathVariable String servicio, ServerWebExchange exchange) {
        return fallback(servicio, exchange);
    }

    private ProblemDetail fallback(String servicio, ServerWebExchange exchange) {
        Throwable causa = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        ProblemDetail problema;
        if (esTimeout(causa)) {
            problema = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    "El servicio " + servicio + " no ha respondido a tiempo");
            problema.setType(URI.create("https://bdo.example/problemas/microservicio-sin-respuesta"));
            problema.setTitle("Microservicio sin respuesta");
        } else {
            problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    causa instanceof CallNotPermittedException
                            ? "El servicio " + servicio + " no esta disponible (circuito abierto); reintentelo en unos segundos"
                            : "El servicio " + servicio + " no esta disponible; reintentelo en unos segundos");
            problema.setType(URI.create("https://bdo.example/problemas/microservicio-no-disponible"));
            problema.setTitle("Microservicio no disponible");
            exchange.getResponse().getHeaders().set("Retry-After", "15");
        }
        problema.setProperty("servicio", servicio);
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(problema.getStatus()));
        log.warn("Fallback del gateway para {}: {} ({})", servicio, problema.getStatus(),
                causa == null ? "sin causa" : causa.getClass().getSimpleName());
        return problema;
    }

    private static boolean esTimeout(Throwable causa) {
        for (Throwable t = causa; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException
                    || t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
