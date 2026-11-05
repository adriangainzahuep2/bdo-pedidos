package com.bdo.pedidos.api.proveedor;

import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException;
import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.error.ProveedorTimeoutException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import java.util.function.Predicate;

/**
 * Clasificacion de los fallos del proveedor logistico. Es la <b>unica</b> fuente de verdad,
 * compartida por los dos adaptadores (reactivo y bloqueante), sobre:
 * <ul>
 *   <li>que fallos se <b>reintentan</b> ({@link #esReintentable});</li>
 *   <li>que fallos cuentan para <b>abrir el circuito</b> ({@link CuentaParaElCircuito});</li>
 *   <li>como se traducen los rechazos de Resilience4j a errores de dominio.</li>
 * </ul>
 *
 * <p>Los adaptadores traducen cada fallo tecnico (timeout de Reactor, error de conexion de
 * Netty, {@code ResourceAccessException}...) a un error de dominio <b>dentro</b> de cada
 * intento, de modo que los reintentos y el circuito razonan siempre sobre errores de
 * dominio y no sobre detalles de la libreria HTTP.
 */
public final class ClasificadorFallosProveedor {

    /** Causa de {@link ProveedorNoDisponibleException} cuando no se pudo conectar. */
    public static final String CAUSA_ERROR_CONEXION = "ERROR_CONEXION";
    public static final String CAUSA_CIRCUITO_ABIERTO = "CIRCUITO_ABIERTO";
    public static final String CAUSA_RATE_LIMIT = "RATE_LIMIT";
    public static final String CAUSA_BULKHEAD = "BULKHEAD";

    private ClasificadorFallosProveedor() {
    }

    /**
     * Fallos transitorios que merece la pena reintentar: 503 (documentado en el enunciado),
     * 429 (limite del proveedor) y errores de conexion. Fallan rapido y el proveedor no ha
     * procesado la peticion.
     *
     * <p><b>Un timeout no se reintenta</b>: el proveedor puede tardar 5 s legitimamente y
     * reintentar consumiria el presupuesto de la peticion sin garantia de exito.
     */
    public static boolean esReintentable(Throwable error) {
        if (error instanceof ProveedorHttpException http) {
            return http.status() == 503 || http.status() == 429;
        }
        return error instanceof ProveedorNoDisponibleException noDisponible
                && CAUSA_ERROR_CONEXION.equals(noDisponible.causa());
    }

    /**
     * Predicado del circuito ({@code record-failure-predicate} en {@code application.yml}).
     *
     * <p>Cuentan como fallo las senales de que el <b>proveedor</b> esta mal: 5xx, 429,
     * timeouts, errores de conexion y respuestas que no cumplen el contrato. Un 4xx (salvo
     * 429) es un error de nuestra peticion: no debe abrir el circuito, porque el proveedor
     * esta sano y abrirlo dejaria sin servicio al resto de pedidos.
     */
    public static final class CuentaParaElCircuito implements Predicate<Throwable> {

        @Override
        public boolean test(Throwable error) {
            if (error instanceof ProveedorHttpException http) {
                return http.status() >= 500 || http.status() == 429;
            }
            if (error instanceof ProveedorNoDisponibleException noDisponible) {
                return CAUSA_ERROR_CONEXION.equals(noDisponible.causa());
            }
            return error instanceof ProveedorTimeoutException
                    || error instanceof ProveedorRespuestaInvalidaException;
        }
    }

    /** Traduce los rechazos de las capas de proteccion (que no llegan a llamar al proveedor). */
    public static Throwable traducirRechazo(Throwable error) {
        if (error instanceof CallNotPermittedException) {
            return new ProveedorNoDisponibleException(CAUSA_CIRCUITO_ABIERTO,
                    "El circuito hacia el proveedor logistico esta abierto: no se ha realizado ninguna llamada", 0);
        }
        if (error instanceof RequestNotPermitted) {
            return new ProveedorNoDisponibleException(CAUSA_RATE_LIMIT,
                    "Se ha superado el limite de peticiones al proveedor logistico", 0);
        }
        if (error instanceof BulkheadFullException) {
            return new ProveedorNoDisponibleException(CAUSA_BULKHEAD,
                    "No hay plazas de concurrencia libres para llamar al proveedor logistico", 0);
        }
        return error;
    }

    /** Devuelve el mismo error de dominio con el numero real de intentos realizados. */
    public static Throwable conIntentos(Throwable error, int intentos) {
        if (error instanceof ProveedorHttpException http) {
            return new ProveedorHttpException(http.status(), http.retryAfterSegundos(), http.cuerpo(),
                    intentos, http.getMessage());
        }
        if (error instanceof ProveedorTimeoutException timeout) {
            return new ProveedorTimeoutException(timeout.getMessage(), timeout.timeoutMs(), intentos);
        }
        if (error instanceof ProveedorNoDisponibleException noDisponible) {
            return new ProveedorNoDisponibleException(noDisponible.causa(), noDisponible.getMessage(), intentos);
        }
        return error;
    }
}
