package com.bdo.pedidos.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Servidor de autorizacion completo (Spring Authorization Server) con los dos clientes de
 * la configuracion: el de servicio ({@code client_credentials}) y la consola web
 * ({@code authorization_code} + PKCE, cliente publico).
 *
 * <p>Los secretos llegan como propiedades del test, igual que en produccion llegan del
 * entorno (los placeholders {@code ${AUTH_...}} de {@code application.yml}).
 */
@SpringBootTest(properties = {
        "AUTH_CLIENT_SECRET_GATEWAY=secreto-de-test",
        "AUTH_USUARIO_PASSWORD=password-de-test"
})
@AutoConfigureMockMvc
@DisplayName("Servidor de autorizacion - client_credentials, authorization_code + PKCE y CORS")
class AuthorizationServerIntegrationTest {

    private static final String CONSOLA = "http://localhost:8080";
    private static final String REDIRECCION = CONSOLA + "/index.html";

    /** Par verificador / desafio S256 del ejemplo del RFC 7636 (apendice B). */
    private static final String VERIFICADOR = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String DESAFIO = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired
    private MockMvc mvc;

    /**
     * {@code /oauth2/authorize} solo acepta los parametros que vienen en la query string,
     * asi que se construye la URL completa en lugar de usar {@code .param()} de MockMvc.
     */
    private static URI autorizar(String... claveValor) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/oauth2/authorize");
        for (int i = 0; i < claveValor.length; i += 2) {
            url.queryParam(claveValor[i], claveValor[i + 1]);
        }
        // URI ya codificada: get(String) trataria la cadena como plantilla y la codificaria
        // otra vez (los %20 de los scopes llegarian como %2520).
        return url.encode().build().toUri();
    }

    private static URI autorizarConPkce(String scope, String state) {
        return autorizar("response_type", "code", "client_id", "consola-web", "redirect_uri", REDIRECCION,
                "scope", scope, "state", state, "code_challenge", DESAFIO, "code_challenge_method", "S256");
    }

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("La salud es publica: el healthcheck de Docker la consulta sin credenciales")
    void saludPublica() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("client_credentials: el cliente de servicio obtiene un JWT firmado con scope como cadena (RFC 9068)")
    void clientCredentials() throws Exception {
        MvcResult resultado = mvc.perform(post("/oauth2/token")
                        .with(httpBasic("pedidos-gateway", "secreto-de-test"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "pedidos.read pedidos.write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(resultado.getResponse().getContentAsString(), "$.access_token"));
        assertThat(jwt.getClaimAsString("scope").split(" ")).containsExactlyInAnyOrder("pedidos.read", "pedidos.write");
        assertThat(jwt.getAudience()).contains("pedidos-api");
        assertThat(jwt.getIssuer()).hasToString("http://localhost:9000");
    }

    @Test
    @DisplayName("client_credentials: un secreto incorrecto se rechaza (401)")
    void secretoIncorrecto() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("pedidos-gateway", "otro-secreto"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authorization_code + PKCE: el usuario obtiene un codigo y la consola lo canjea sin secreto")
    void authorizationCodeConPkce() throws Exception {
        MvcResult autorizacion = mvc.perform(get(autorizarConPkce(
                                "pedidos.read pedidos.write clientes.read clientes.write", "estado-123"))
                        .with(user("demo")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECCION + "?code=*&state=estado-123"))
                .andReturn();

        String codigo = UriComponentsBuilder.fromUriString(autorizacion.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("code");

        MvcResult token = mvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, CONSOLA)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", codigo)
                        .param("redirect_uri", REDIRECCION)
                        .param("client_id", "consola-web")
                        .param("code_verifier", VERIFICADOR))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLA))
                .andReturn();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(token.getResponse().getContentAsString(), "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("demo");
        assertThat(jwt.getClaimAsString("azp")).isEqualTo("consola-web");
        assertThat(jwt.getClaimAsString("scope").split(" "))
                .containsExactlyInAnyOrder("pedidos.read", "pedidos.write", "clientes.read", "clientes.write");
    }

    @Test
    @DisplayName("authorization_code: un verificador PKCE incorrecto no permite canjear el codigo")
    void verificadorIncorrecto() throws Exception {
        MvcResult autorizacion = mvc.perform(get(autorizarConPkce("pedidos.read", "s"))
                        .with(user("demo")))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String codigo = UriComponentsBuilder.fromUriString(autorizacion.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("code");

        mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", codigo)
                        .param("redirect_uri", REDIRECCION)
                        .param("client_id", "consola-web")
                        .param("code_verifier", "otro-verificador-que-no-corresponde-con-el-desafio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @DisplayName("authorization_code sin PKCE: el cliente publico lo exige y se rechaza")
    void sinPkce() throws Exception {
        mvc.perform(get(autorizar("response_type", "code", "client_id", "consola-web",
                                "redirect_uri", REDIRECCION, "scope", "pedidos.read", "state", "s"))
                        .with(user("demo")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECCION + "?error=invalid_request*"));
    }

    @Test
    @DisplayName("authorization_code con una redireccion no registrada: se rechaza sin redirigir")
    void redireccionNoRegistrada() throws Exception {
        mvc.perform(get(autorizar("response_type", "code", "client_id", "consola-web",
                                "redirect_uri", "https://atacante.example/robar", "scope", "pedidos.read",
                                "state", "s", "code_challenge", DESAFIO, "code_challenge_method", "S256"))
                        .with(user("demo")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sin sesion, el navegador va a la pagina de inicio de sesion")
    void sinSesionVaAlLogin() throws Exception {
        mvc.perform(get(autorizarConPkce("pedidos.read", "s"))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("CORS: la consola web puede llamar a /oauth2/token; otro origen no")
    void cors() throws Exception {
        mvc.perform(options("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, CONSOLA)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLA));

        for (String origen : List.of("https://atacante.example", "http://localhost:3000")) {
            mvc.perform(options("/oauth2/token")
                            .header(HttpHeaders.ORIGIN, origen)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden());
        }
    }
}
