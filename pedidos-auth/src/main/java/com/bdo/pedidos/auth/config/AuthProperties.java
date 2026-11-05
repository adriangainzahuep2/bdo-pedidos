package com.bdo.pedidos.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Configuracion de los clientes OAuth2 registrados.
 *
 * <p>Los secretos llegan del entorno (variable de entorno o Spring Cloud Config cifrado);
 * <b>no estan en el codigo fuente</b>. Si no se define ningun cliente, la aplicacion falla
 * en el arranque en lugar de emitir tokens con credenciales por defecto.
 */
@Validated
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** Emisor del token (claim {@code iss}). Debe coincidir con el que validan los recursos. */
    @NotBlank
    private String issuer = "http://localhost:9000";

    /** Vida del token de acceso en minutos. */
    private long accessTokenMinutos = 60;

    /** Audiencias para las que se puede pedir token (claim {@code aud}). */
    @NotEmpty
    private Set<String> audiencias = Set.of("pedidos-api", "pedidos-cliente", "pedidos-gateway");

    /** Usuario de demostracion para el flujo de navegador (authorization_code + PKCE). */
    @NotBlank
    private String usuario = "demo";

    /** Contrasena del usuario de demostracion: llega del entorno, nunca del codigo. */
    @NotBlank
    private String password;

    /** Clientes registrados. */
    private List<Cliente> clientes = new ArrayList<>();

    /** Cliente OAuth2. */
    public static class Cliente {

        /** Identificador del cliente. */
        @NotBlank
        private String clientId;

        /**
         * Secreto del cliente. Vacio para clientes publicos (aplicaciones web que usan PKCE).
         */
        private String clientSecret;

        /** Permisos que puede solicitar. */
        @NotEmpty
        private Set<String> scopes = Set.of();

        /** Tipo de autorizacion: {@code client_credentials} o {@code authorization_code}. */
        @NotEmpty
        private Set<String> grantTypes = Set.of("client_credentials");

        /** URI de redireccion (solo para {@code authorization_code}). */
        private String redirectUri;

        /** Si es {@code true}, el cliente es publico y no tiene secreto (PKCE obligatorio). */
        private boolean publico = false;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public Set<String> getScopes() {
            return scopes;
        }

        public void setScopes(Set<String> scopes) {
            this.scopes = scopes;
        }

        public Set<String> getGrantTypes() {
            return grantTypes;
        }

        public void setGrantTypes(Set<String> grantTypes) {
            this.grantTypes = grantTypes;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public boolean isPublico() {
            return publico;
        }

        public void setPublico(boolean publico) {
            this.publico = publico;
        }
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getAccessTokenMinutos() {
        return accessTokenMinutos;
    }

    public void setAccessTokenMinutos(long accessTokenMinutos) {
        this.accessTokenMinutos = accessTokenMinutos;
    }

    public Set<String> getAudiencias() {
        return audiencias;
    }

    public void setAudiencias(Set<String> audiencias) {
        this.audiencias = audiencias;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public List<Cliente> getClientes() {
        return clientes;
    }

    public void setClientes(List<Cliente> clientes) {
        this.clientes = clientes;
    }
}
