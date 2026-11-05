package com.bdo.pedidos.gateway.filtro;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Genera y propaga el identificador de correlacion ({@code X-Correlation-Id}).
 *
 * <p>El gateway es el primero que ve la peticion, asi que es el sitio natural para
 * asignar el identificador: a partir de ahi viaja por todos los servicios (y por los logs)
 * y vuelve al cliente en la respuesta, de modo que una incidencia se puede reconstruir
 * de extremo a extremo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroCorrelacion implements GlobalFilter {

    public static final String CABECERA = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlacion = exchange.getRequest().getHeaders().getFirst(CABECERA);
        if (correlacion == null || correlacion.isBlank()) {
            correlacion = UUID.randomUUID().toString();
        }
        String identificador = correlacion;
        exchange.getResponse().getHeaders().set(CABECERA, identificador);
        exchange.getAttributes().put(CABECERA, identificador);
        // La peticion que se reenvia al microservicio debe llevar el mismo identificador
        // (sobre todo cuando lo acaba de generar el gateway). Las cabeceras de la peticion
        // son de solo lectura en este punto: se expone una copia con la cabecera anadida.
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.putAll(exchange.getRequest().getHeaders());
        cabeceras.set(CABECERA, identificador);
        ServerHttpRequest conCorrelacion = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                return cabeceras;
            }
        };
        return chain.filter(exchange.mutate().request(conCorrelacion).build());
    }
}
