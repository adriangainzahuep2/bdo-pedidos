package com.bdo.pedidos.testkit;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Genera tokens JWT reales (HS256) para las suites de integracion.
 *
 * <p>Se prefiere un token real a "mockJwt()" porque los tests levantan el servidor de
 * verdad (puerto aleatorio): asi se ejercita la cadena de seguridad completa (filtro de
 * Resource Server, validacion de firma, de emisor y de caducidad, y conversion de
 * permisos), no una simulacion. Es lo mas parecido a lo que hace un cliente real.
 *
 * <p>La clave es una constante de test: no es un secreto y no protege nada real.
 */
public final class TokenDePrueba {

    public static final String EMISOR = "http://localhost:9000";

    public static final SecretKey CLAVE = new SecretKeySpec(
            "clave-de-pruebas-bdo-pedidos-2026".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private TokenDePrueba() {
    }

    /** @return un token con los permisos indicados en el claim {@code scope}. */
    public static String token(String... permisos) {
        Instant ahora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(EMISOR)
                .subject("cliente-de-prueba")
                .issuedAt(ahora)
                .expiresAt(ahora.plus(1, ChronoUnit.HOURS))
                .claim("scope", String.join(" ", Arrays.asList(permisos)))
                .claim("client_id", "suite-de-pruebas")
                .build();

        JWKSource<SecurityContext> fuente = (selector, contexto) ->
                selector.select(new JWKSet(new OctetSequenceKey.Builder(CLAVE).build()));

        NimbusJwtEncoder encoder = new NimbusJwtEncoder(fuente);
        JwsHeader cabecera = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(cabecera, claims)).getTokenValue();
    }
}
