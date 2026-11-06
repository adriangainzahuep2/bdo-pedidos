package com.bdo.pedidos.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Seguridad del servicio de pedidos: <b>Resource Server OAuth2</b> con JWT (RS256).
 *
 * <ul>
 *   <li>El token lo emite el servicio de autorizacion ({@code pedidos-auth}) y se valida
 *       contra su JWKS; no hay validacion de contrasenas ni sesiones en este servicio.</li>
 *   <li>Los permisos se derivan del claim {@code scope} con el conversor estandar de
 *       Spring Security, que acepta tanto una cadena separada por espacios como una lista
 *       (que es lo que emite Spring Authorization Server 1.x): {@code SCOPE_pedidos.write}
 *       para escribir y {@code SCOPE_pedidos.read} para leer.</li>
 *   <li>Los endpoints de salud de Actuator quedan abiertos para los probes de orquestacion.</li>
 *   <li>La consola estatica de demostracion ({@code /}, {@code /index.html}, JS y CSS) es
 *       publica: no contiene datos, y todas las llamadas que hace a la API llevan token.</li>
 * </ul>
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
                        .pathMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/pkce.js", "/styles.css",
                                "/favicon.ico").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/pedidos/**").hasAuthority("SCOPE_pedidos.write")
                        .pathMatchers(HttpMethod.GET, "/api/pedidos/**").hasAuthority("SCOPE_pedidos.read")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
