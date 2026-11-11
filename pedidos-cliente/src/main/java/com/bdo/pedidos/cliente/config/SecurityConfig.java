package com.bdo.pedidos.cliente.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.time.Clock;

/**
 * Seguridad del servicio de clientes: Resource Server OAuth2 (JWT), igual que el servicio
 * de pedidos. Los permisos tambien se derivan del claim {@code scope}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        // Metricas para Prometheus (sin datos de negocio). En produccion, en un
                        // puerto de gestion propio solo accesible desde la red interna.
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/clientes/**").hasAuthority("SCOPE_clientes.write")
                        .pathMatchers(HttpMethod.GET, "/api/clientes/**").hasAuthority("SCOPE_clientes.read")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
