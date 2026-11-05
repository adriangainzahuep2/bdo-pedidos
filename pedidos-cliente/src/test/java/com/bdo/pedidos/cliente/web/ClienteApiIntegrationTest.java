package com.bdo.pedidos.cliente.web;

import com.bdo.pedidos.cliente.persistencia.ClienteRepository;
import com.bdo.pedidos.testkit.TokenDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Test de integracion del microservicio de clientes: alta, consulta, unicidad del email,
 * validacion de entrada y autorizacion por permisos.
 *
 * <p>El servidor se levanta de verdad (puerto aleatorio) sobre H2 en memoria y con la
 * cadena de seguridad completa (JWT HS256 firmado por el decoder de test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API de clientes - alta, consulta y control de acceso")
class ClienteApiIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @SpyBean
    private ClienteRepository repositorio;

    /**
     * Decoder real de los tests: valida firma HMAC, emisor y caducidad, igual que en
     * produccion (donde valida la firma RSA contra el JWKS del servidor de autorizacion).
     */
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

    private WebTestClient autorizado(String... permisos) {
        return webTestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + TokenDePrueba.token(permisos))
                .build();
    }

    @Test
    @DisplayName("da de alta un cliente y lo devuelve con el identificador asignado")
    void altaDeCliente() {
        String email = "alta-" + System.nanoTime() + "@bdo-es.example";

        ClienteApiIntegrationTest.ClienteResponse creado = autorizado("clientes.write")
                .post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombre\":\"Empresa Demo S.L.\",\"email\":\"" + email + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().value("Location", org.hamcrest.Matchers.startsWith("/api/clientes/"))
                .expectBody(ClienteApiIntegrationTest.ClienteResponse.class)
                .returnResult().getResponseBody();

        assertThat(creado).isNotNull();
        assertThat(creado.id()).isPositive();
        assertThat(creado.nombre()).isEqualTo("Empresa Demo S.L.");
        assertThat(creado.email()).isEqualTo(email);

        // Y se puede consultar despues.
        autorizado("clientes.read")
                .get().uri("/api/clientes/" + creado.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(email);
    }

    @Test
    @DisplayName("rechaza el alta con un email ya existente (409)")
    void emailDuplicado() {
        String email = "duplicado-" + System.nanoTime() + "@bdo-es.example";
        String cuerpo = "{\"nombre\":\"Repetida S.L.\",\"email\":\"" + email + "\"}";

        autorizado("clientes.write").post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo)
                .exchange().expectStatus().isCreated();

        autorizado("clientes.write").post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.es/clientes/problemas/email-duplicado");
    }

    @Test
    @DisplayName("dos altas simultaneas con el mismo email: la restriccion unica responde 409, no 500")
    void emailDuplicadoEnCarrera() {
        String email = "carrera-" + System.nanoTime() + "@bdo-es.example";
        // La otra peticion ya ha insertado el cliente...
        repositorio.insertar("Primera S.L.", email, Instant.now()).block();
        // ...pero esta paso la comprobacion previa antes de que existiera (ventana de carrera).
        doReturn(Mono.empty()).when(repositorio).buscarPorEmail(anyString());

        autorizado("clientes.write").post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombre\":\"Segunda S.L.\",\"email\":\"" + email + "\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.es/clientes/problemas/email-duplicado");
    }

    @Test
    @DisplayName("devuelve 404 al consultar un cliente inexistente")
    void clienteNoEncontrado() {
        autorizado("clientes.read")
                .get().uri("/api/clientes/999999999")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.es/clientes/problemas/cliente-no-encontrado");
    }

    @Test
    @DisplayName("valida la peticion: nombre obligatorio y email con formato (400)")
    void peticionInvalida() {
        autorizado("clientes.write")
                .post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombre\":\"\",\"email\":\"no-es-un-email\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://bdo.es/clientes/problemas/peticion-invalida")
                .jsonPath("$.errores").isArray();
    }

    @Test
    @DisplayName("exige token: 401 sin cabecera y 403 sin el permiso adecuado")
    void controlDeAcceso() {
        webTestClient.post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombre\":\"X\",\"email\":\"x@bdo-es.example\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        // Token valido, pero sin el permiso "clientes.write".
        autorizado("clientes.read").post().uri("/api/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombre\":\"X\",\"email\":\"x2@bdo-es.example\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    /** Respuesta de cliente (espejo del DTO del controlador). */
    record ClienteResponse(long id, String nombre, String email) {
    }
}
