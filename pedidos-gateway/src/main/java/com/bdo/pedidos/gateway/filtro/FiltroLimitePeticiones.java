package com.bdo.pedidos.gateway.filtro;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

/**
 * Limite de peticiones global del gateway.
 *
 * <p>Se aplica con {@code reservePermission()}, que no bloquea: si no hay permiso
 * disponible se responde 429 de inmediato (fallo rapido) en lugar de encolar peticiones y
 * agotar la memoria. El limite es por instancia del gateway; en un despliegue con N
 * instancias el limite efectivo es N x el configurado.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class FiltroLimitePeticiones implements GlobalFilter {

    /** Nombre de la instancia de Resilience4j. */
    public static final String INSTANCIA = "apiGateway";

    private final RateLimiter limitador;

    public FiltroLimitePeticiones(RateLimiterRegistry registry) {
        this.limitador = registry.rateLimiter(INSTANCIA);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // reservePermission() no bloquea: devuelve -1 si no hay permiso disponible.
        long esperaNanos = limitador.reservePermission();
        if (esperaNanos == -1) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("Retry-After", "1");
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
