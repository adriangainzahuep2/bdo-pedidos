package com.bdo.pedidos.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Configuracion del servidor de autorizacion OAuth2.
 *
 * <h2>Decisiones</h2>
 * <ul>
 *   <li><b>RS256 con clave RSA generada en el arranque.</b> La clave publica se publica en
 *       {@code /oauth2/jwks} y la validan los recursos: nadie mas que el emisor conoce la
 *       clave privada. En produccion la clave debe venir de un almacen de claves (Vault,
 *       KMS) para que sobreviva a un reinicio; en este entorno se genera en memoria y asi
 *       se documenta.</li>
 *   <li><b>Los secretos de los clientes se guardan cifrados</b> (delegating password
 *       encoder) y llegan del entorno, nunca del codigo.</li>
 *   <li>Dos tipos de cliente: {@code client_credentials} para los servicios (el API Gateway,
 *       scripts) y {@code authorization_code} + PKCE para las aplicaciones web (consola
 *       HTML). Un navegador jamas debe usar {@code client_credentials}: tendria que llevar
 *       el secreto del cliente en el codigo que se descarga en el navegador.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    /** Cadena de filtros del propio servidor de autorizacion (prioridad mas alta). */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults());
        http.exceptionHandling(excepciones -> excepciones.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Cadena de filtros del resto de la aplicacion: solo usuarios autenticados. */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(autorizaciones -> autorizaciones.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    /** Fuente de claves JWT (RSA). */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = generarClaveRsa();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** Ajustes del servidor de autorizacion (emisor, rutas estandar de OAuth2). */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(AuthProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .build();
    }

    /** Repositorio de clientes registrados, alimentado desde la configuracion externa. */
    @Bean
    public RegisteredClientRepository registeredClientRepository(AuthProperties properties) {
        PasswordEncoder codificador = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        List<RegisteredClient> clientes = new ArrayList<>();

        for (AuthProperties.Cliente configuracion : properties.getClientes()) {
            boolean conCodigo = configuracion.getGrantTypes()
                    .contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
            boolean conCredenciales = configuracion.getGrantTypes()
                    .contains(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue());

            RegisteredClient.Builder constructor = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(configuracion.getClientId())
                    .redirectUri(conCodigo && configuracion.getRedirectUri() != null
                            ? configuracion.getRedirectUri() : "http://127.0.0.1:0/callback")
                    .scopes(permisos -> permisos.addAll(configuracion.getScopes()))
                    .clientAuthenticationMethod(configuracion.isPublico()
                            ? ClientAuthenticationMethod.NONE : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(java.time.Duration.ofMinutes(properties.getAccessTokenMinutos()))
                            .build());

            if (conCredenciales) {
                constructor.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
            }
            if (conCodigo) {
                constructor.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
                constructor.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            }

            if (!configuracion.isPublico()) {
                if (configuracion.getClientSecret() == null || configuracion.getClientSecret().isBlank()) {
                    throw new IllegalStateException(
                            "Falta el secreto del cliente OAuth '" + configuracion.getClientId()
                                    + "': debe llegar del entorno (AUTH_CLIENT_SECRET_<ID>), nunca del codigo fuente");
                }
                constructor.clientSecret(codificador.encode(configuracion.getClientSecret()));
            } else {
                // Cliente publico: PKCE es obligatorio (no hay secreto que guardar).
                constructor.clientSettings(ClientSettings.builder().requireProofKey(true).build());
            }

            clientes.add(constructor.build());
        }

        if (clientes.isEmpty()) {
            throw new IllegalStateException(
                    "No hay clientes OAuth configurados: el servidor de autorizacion no puede emitir tokens");
        }
        return new InMemoryRegisteredClientRepository(clientes);
    }

    /**
     * Anade el claim {@code aud} al token: los recursos pueden exigir la audiencia correcta
     * y evitar que un token emitido para otro servicio se pueda reutilizar.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(AuthProperties properties) {
        return context -> {
            context.getClaims().claim("aud", List.copyOf(properties.getAudiencias()));
            context.getClaims().claim("azp", context.getRegisteredClient().getClientId());
            // El claim "scope" se emite como cadena separada por espacios (RFC 9068).
            // Spring Authorization Server 1.x lo emite por defecto como lista; los recursos
            // aceptan ambos formatos, pero el estandar es la cadena.
            context.getClaims().claim("scope", String.join(" ", context.getAuthorizedScopes()));
        };
    }

    /**
     * Usuario de demostracion para el flujo {@code authorization_code} (consola HTML).
     * Sus credenciales llegan del entorno; en produccion se sustituye por un proveedor
     * de identidad corporativo.
     */
    @Bean
    public UserDetailsService userDetailsService(AuthProperties properties) {
        PasswordEncoder codificador = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        UserDetails usuario = User.withUsername(properties.getUsuario())
                .password(codificador.encode(properties.getPassword()))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(usuario);
    }

    /** Genera el par de claves RSA que firma los tokens. */
    private static RSAKey generarClaveRsa() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            KeyPair par = generador.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) par.getPublic())
                    .privateKey((RSAPrivateKey) par.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception error) {
            throw new IllegalStateException("No se pudo generar el par de claves RSA del servidor de autorizacion", error);
        }
    }
}
