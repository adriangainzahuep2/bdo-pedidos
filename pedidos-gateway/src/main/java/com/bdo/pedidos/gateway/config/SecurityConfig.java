package com.bdo.pedidos.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Seguridad del API Gateway.
 *
 * <p>El gateway valida el JWT <b>antes</b> de enrutar la peticion: un token invalido no
 * llega nunca a un microservicio. Los microservicios vuelven a validarlo (defensa en
 * profundidad: un servicio puede ser alcanzado sin pasar por el gateway).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        // Metricas para Prometheus (sin datos de negocio). En produccion, en un
                        // puerto de gestion propio solo accesible desde la red interna.
                        .pathMatchers("/actuator/prometheus").permitAll()
                        // Ficheros estaticos de la consola web: no contienen datos; todas las
                        // llamadas que hace la consola a la API llevan su token.
                        .pathMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/pkce.js",
                                "/styles.css", "/favicon.ico").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
