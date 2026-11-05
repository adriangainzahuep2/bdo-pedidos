package com.bdo.pedidos.dominio.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de las excepciones tipadas del dominio.
 *
 * <p>Cada error de negocio lleva sus propios datos (codigo HTTP, numero de intentos,
 * identificador afectado). Esta suite comprueba que esos datos se conservan: son los que
 * usa el manejador global de errores para construir la respuesta ProblemDetail y los que
 * alimentan la traza de auditoria del intento de confirmacion.
 */
@DisplayName("Excepciones tipadas del dominio")
class ExcepcionesDelDominioTest {

    @Test
    @DisplayName("ProveedorHttpException conserva estado, Retry-After, cuerpo e intentos")
    void proveedorHttp() {
        ProveedorHttpException error = new ProveedorHttpException(
                503, 7, "servicio no disponible", 3, "El proveedor respondio con HTTP 503");

        assertThat(error.status()).isEqualTo(503);
        assertThat(error.retryAfterSegundos()).isEqualTo(7);
        assertThat(error.cuerpo()).isEqualTo("servicio no disponible");
        assertThat(error.intentos()).isEqualTo(3);
        assertThat(error.getMessage()).contains("503");
    }

    @Test
    @DisplayName("ProveedorTimeoutException conserva el limite de tiempo y los intentos")
    void proveedorTimeout() {
        ProveedorTimeoutException error = new ProveedorTimeoutException(
                "Se agoto el tiempo de espera", 6_000L, 1);

        assertThat(error.timeoutMs()).isEqualTo(6_000L);
        assertThat(error.intentos()).isEqualTo(1);
    }

    @Test
    @DisplayName("ProveedorNoDisponibleException conserva la causa y los intentos")
    void proveedorNoDisponible() {
        ProveedorNoDisponibleException error = new ProveedorNoDisponibleException(
                "circuito abierto", "El proveedor no esta disponible", 0);

        assertThat(error.causa()).isEqualTo("circuito abierto");
        assertThat(error.intentos()).isZero();
    }

    @Test
    @DisplayName("ClienteNoEncontradoException conserva el identificador del cliente")
    void clienteNoEncontrado() {
        assertThat(new ClienteNoEncontradoException(42L).clienteId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("los errores de dominio son RuntimeException no comprobadas")
    void sonErroresNoComprobados() {
        assertThat(new PedidoNoEncontradoException(1L)).isInstanceOf(RuntimeException.class);
        assertThat(new TransicionDeEstadoInvalidaException("estado")).isInstanceOf(ErrorDominio.class);
        assertThat(new ConfirmacionEnCursoException(1L)).isInstanceOf(ErrorDominio.class);
        assertThat(new ProveedorRespuestaInvalidaException("respuesta")).isInstanceOf(ErrorDominio.class);
    }
}
