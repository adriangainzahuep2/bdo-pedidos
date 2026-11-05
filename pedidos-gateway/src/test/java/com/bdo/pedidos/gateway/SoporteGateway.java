package com.bdo.pedidos.gateway;

import com.bdo.pedidos.testkit.TokenDePrueba;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** Piezas comunes de los tests del gateway: microservicio simulado y decoder JWT de test. */
final class SoporteGateway {

    private SoporteGateway() {
    }

    static WireMockServer arrancarMicroservicio() {
        WireMockServer servidor = new WireMockServer(options().dynamicPort());
        servidor.start();
        return servidor;
    }

    /**
     * Sustituye las rutas lb:// (que necesitan Eureka) por una ruta directa al microservicio
     * simulado. Las listas de propiedades se reemplazan enteras, asi que solo existe esta ruta.
     */
    static void rutaAlMicroservicio(DynamicPropertyRegistry registro, WireMockServer microservicio) {
        registro.add("spring.cloud.gateway.routes[0].id", () -> "pedidos-api");
        registro.add("spring.cloud.gateway.routes[0].uri", () -> "http://localhost:" + microservicio.port());
        registro.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/api/pedidos/**");
        registro.add("eureka.client.enabled", () -> "false");
    }

    /** Decoder real (HS256) para los tokens de {@link TokenDePrueba}. */
    @TestConfiguration
    static class DecoderDePrueba {

        @Bean
        @Primary
        ReactiveJwtDecoder reactiveJwtDecoderDePrueba() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(TokenDePrueba.CLAVE)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenDePrueba.EMISOR));
            return decoder;
        }
    }
}
