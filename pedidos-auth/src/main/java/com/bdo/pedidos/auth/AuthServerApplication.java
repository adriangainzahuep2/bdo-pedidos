package com.bdo.pedidos.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servidor de autorizacion OAuth2 (Spring Authorization Server).
 *
 * <p>Emite tokens JWT firmados con RS256. Los recursos (gateway y microservicios) los
 * validan contra el JWKS publicado en {@code /oauth2/jwks}, de modo que ninguno necesita
 * compartir clave con el emisor.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
