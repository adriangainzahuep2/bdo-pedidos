package com.bdo.pedidos.api.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Beans transversales: el reloj (inyectado, para que los tests puedan congelar el tiempo)
 * y el circuito del servicio de clientes.
 */
@Configuration(proxyBeanMethods = false)
public class AplicacionConfig {

    /**
     * Reloj del sistema en UTC. Inyectarlo en lugar de llamar a {@code Instant.now()} en el
     * codigo permite que los tests verifiquen las marcas de tiempo de forma determinista.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Circuito del servicio de clientes: permite degradar con elegancia si el hermano cae. */
    @Bean
    public CircuitBreaker circuitBreakerClientes(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("servicioClientes");
    }
}
