package com.bdo.pedidos.api.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Filtro que propaga un identificador de correlacion.
 *
 * <p>El identificador viaja en la cabecera {@code X-Correlation-Id}: lo genera el API Gateway
 * si no viene (o este filtro, si se llama al servicio directamente), se devuelve en la
 * respuesta y queda en el Contexto de Reactor ({@link #CONTEXTO}) y en los atributos del
 * intercambio para quien lo necesite.
 *
 * <p>Decision: <b>no</b> se escribe en el MDC de SLF4J. El MDC es un {@code ThreadLocal} y en
 * WebFlux una peticion cambia de hilo varias veces: ponerlo aqui y quitarlo en
 * {@code doFinally} lo dejaria pegado a hilos que ya atienden otras peticiones. Llevarlo a
 * los logs exige propagacion de contexto (Micrometer Context Propagation); queda anotado en
 * el README como mejora.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroCorrelacion implements WebFilter {

    public static final String CABECERA = "X-Correlation-Id";

    /** Clave del identificador en el Contexto de Reactor. */
    public static final String CONTEXTO = "bdo.correlacion";

    private static final int LONGITUD_MAXIMA = 64;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(CABECERA);
        // No se confia en una cabecera arbitraria del cliente: si no es razonable, se genera.
        String identificador = esValido(recibido) ? recibido : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(CABECERA, identificador);
        exchange.getAttributes().put(CABECERA, identificador);
        return chain.filter(exchange)
                .contextWrite(contexto -> contexto.put(CONTEXTO, identificador));
    }

    private static boolean esValido(String valor) {
        return valor != null && !valor.isBlank() && valor.length() <= LONGITUD_MAXIMA
                && valor.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
}
