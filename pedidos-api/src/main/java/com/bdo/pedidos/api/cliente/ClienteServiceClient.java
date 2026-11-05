package com.bdo.pedidos.api.cliente;

import com.bdo.pedidos.api.config.ClientesProperties;
import com.bdo.pedidos.api.web.FiltroPropagacionToken;
import com.bdo.pedidos.dominio.modelo.Cliente;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Adaptador de salida hacia el microservicio de clientes.
 *
 * <p>El servicio de pedidos <b>no</b> comparte base de datos con el de clientes: cada
 * microservicio es dueno de sus datos. La validacion del cliente al crear el pedido se hace
 * por HTTP, con descubrimiento y balanceo de carga, con un timeout propio (2 s) y con un
 * circuito para no arrastrar al cliente final un fallo del servicio hermano.
 *
 * <ul>
 *   <li>{@code 200} -&gt; el cliente.</li>
 *   <li>{@code 404} -&gt; {@link Mono#empty()}: el cliente no existe (la capa de aplicacion
 *       responde 422). No cuenta como fallo del circuito.</li>
 *   <li>Cualquier otro codigo, un timeout o un error de red -&gt;
 *       {@link ClienteServiceNoDisponibleException} (503).</li>
 * </ul>
 *
 * <p>La llamada es autenticada: se propaga el token de entrada (ver
 * {@link FiltroPropagacionToken}).
 */
@Component
public class ClienteServiceClient implements ClientePort {

    private final WebClient webClient;
    private final ClientesProperties properties;
    private final CircuitBreaker circuitBreaker;

    public ClienteServiceClient(@Qualifier("webClientClientes") WebClient webClient,
                                ClientesProperties properties,
                                @Qualifier("circuitBreakerClientes") CircuitBreaker circuitBreaker) {
        this.webClient = webClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Mono<Cliente> buscarPorId(long id) {
        return Mono.deferContextual(contexto -> {
            String autorizacion = contexto.getOrDefault(FiltroPropagacionToken.CONTEXTO_TOKEN, "");
            return webClient.get()
                    .uri("/api/clientes/{id}", id)
                    .headers(cabeceras -> {
                        if (!autorizacion.isBlank()) {
                            cabeceras.set(HttpHeaders.AUTHORIZATION, autorizacion);
                        }
                    })
                    .exchangeToMono(respuesta -> traducirRespuesta(respuesta, id))
                    .timeout(properties.getTimeout())
                    .map(respuesta -> new Cliente(respuesta.id(), respuesta.nombre(), respuesta.email()))
                    .transform(CircuitBreakerOperator.of(circuitBreaker))
                    .onErrorMap(error -> error instanceof ClienteServiceNoDisponibleException
                            ? error
                            : new ClienteServiceNoDisponibleException(mensaje(error), error));
        });
    }

    private static Mono<ClienteResponse> traducirRespuesta(ClientResponse respuesta, long id) {
        if (respuesta.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return respuesta.releaseBody().then(Mono.empty());
        }
        if (respuesta.statusCode().is2xxSuccessful()) {
            return respuesta.bodyToMono(ClienteResponse.class);
        }
        return respuesta.releaseBody().then(Mono.error(new ClienteServiceNoDisponibleException(
                "El servicio de clientes respondio con HTTP " + respuesta.statusCode().value()
                        + " al consultar el cliente " + id, null)));
    }

    private static String mensaje(Throwable error) {
        if (error instanceof CallNotPermittedException) {
            return "El circuito hacia el servicio de clientes esta abierto";
        }
        return "No se pudo consultar el servicio de clientes: " + error.getMessage();
    }

    /** Respuesta del servicio de clientes. */
    public record ClienteResponse(long id, String nombre, String email) {
    }
}
