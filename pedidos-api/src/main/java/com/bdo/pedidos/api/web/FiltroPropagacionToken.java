package com.bdo.pedidos.api.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Propaga el token del cliente final a las llamadas <b>entre microservicios</b>.
 *
 * <p>Cuando el servicio de pedidos valida el cliente en el servicio de clientes, esa
 * llamada es una llamada autenticada mas: el token del usuario (o del cliente de servicio)
 * debe viajar en la cabecera {@code Authorization}. Si no se propaga, el servicio de
 * clientes responde 401 y la creacion del pedido falla.
 *
 * <p>El token se guarda en el <b>Contexto de Reactor</b> (no en un campo), porque el
 * Contexto es lo unico que viaja con la peticion en una cadena reactiva: asi dos
 * peticiones concurrentes nunca comparten token.
 *
 * <p><b>Nota de seguridad:</b> al propagar el token de entrada, el servicio de pedidos
 * actua como cliente honesto del servicio de clientes. En un entorno con politicas de
 * delegacion distintas (por ejemplo, el servicio de pedidos con permisos mas amplios que
 * el usuario final), la alternativa es un token de servicio propio emitido con
 * {@code client_credentials}; se documenta como opcion en el README.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class FiltroPropagacionToken implements WebFilter {

    /** Clave del token dentro del Contexto de Reactor. */
    public static final String CONTEXTO_TOKEN = "bdo.token.portador";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String autorizacion = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (autorizacion == null || autorizacion.isBlank()) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange)
                .contextWrite(contexto -> contexto.put(CONTEXTO_TOKEN, autorizacion));
    }
}
