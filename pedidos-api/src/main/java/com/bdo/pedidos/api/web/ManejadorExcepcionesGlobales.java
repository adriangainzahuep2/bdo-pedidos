package com.bdo.pedidos.api.web;

import com.bdo.pedidos.api.cliente.ClienteServiceNoDisponibleException;
import com.bdo.pedidos.dominio.error.ClienteNoEncontradoException;
import com.bdo.pedidos.dominio.error.ConfirmacionEnCursoException;
import com.bdo.pedidos.dominio.error.DatosInvalidosException;
import com.bdo.pedidos.dominio.error.PedidoNoEncontradoException;
import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException;
import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.error.ProveedorTimeoutException;
import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Traduccion de errores de dominio y de infraestructura a respuestas HTTP.
 *
 * <p>Se usa {@link ProblemDetail} (RFC 9457), el formato estandar para problemas HTTP en
 * Spring Framework 6: el cliente recibe siempre la misma estructura (tipo, titulo, estado,
 * detalle y, cuando procede, los errores de campo).
 *
 * <p>Mapa de errores:
 * <table border="1">
 *   <caption>Mapa de codigos HTTP</caption>
 *   <tr><th>Error</th><th>HTTP</th><th>Por que</th></tr>
 *   <tr><td>PedidoNoEncontrado</td><td>404</td><td>el recurso no existe</td></tr>
 *   <tr><td>DatosInvalidos</td><td>422</td><td>peticion sintacticamente valida pero viola una regla</td></tr>
 *   <tr><td>TransicionDeEstadoInvalida / ConfirmacionEnCurso</td><td>409</td><td>conflicto de estado</td></tr>
 *   <tr><td>ProveedorHttp / ProveedorRespuestaInvalida</td><td>502</td><td>el proveedor fallo: el error es de la dependencia, no del cliente</td></tr>
 *   <tr><td>ProveedorTimeout</td><td>504</td><td>el proveedor no respondio a tiempo</td></tr>
 *   <tr><td>ProveedorNoDisponible / ClienteServiceNoDisponible</td><td>503</td><td>servicio no disponible: reintentable</td></tr>
 * </table>
 */
@RestControllerAdvice
public class ManejadorExcepcionesGlobales {

    private static final Logger log = LoggerFactory.getLogger(ManejadorExcepcionesGlobales.class);

    private static final String TIPO_BASE = "https://bdo.es/pedidos/problemas/";

    @ExceptionHandler(PedidoNoEncontradoException.class)
    public Mono<ProblemDetail> pedidoNoEncontrado(PedidoNoEncontradoException error) {
        return problema(HttpStatus.NOT_FOUND, "pedido-no-encontrado", error.getMessage());
    }

    @ExceptionHandler(ClienteNoEncontradoException.class)
    public Mono<ProblemDetail> clienteNoEncontrado(ClienteNoEncontradoException error) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "cliente-no-encontrado", error.getMessage());
    }

    @ExceptionHandler(DatosInvalidosException.class)
    public Mono<ProblemDetail> datosInvalidos(DatosInvalidosException error) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "datos-invalidos", error.getMessage());
    }

    @ExceptionHandler(ConfirmacionEnCursoException.class)
    public Mono<ProblemDetail> confirmacionEnCurso(ConfirmacionEnCursoException error) {
        return problema(HttpStatus.CONFLICT, "confirmacion-en-curso", error.getMessage());
    }

    @ExceptionHandler(TransicionDeEstadoInvalidaException.class)
    public Mono<ProblemDetail> transicionInvalida(TransicionDeEstadoInvalidaException error) {
        return problema(HttpStatus.CONFLICT, "transicion-de-estado-invalida", error.getMessage());
    }

    @ExceptionHandler(ProveedorTimeoutException.class)
    public Mono<ProblemDetail> proveedorTimeout(ProveedorTimeoutException error) {
        log.warn("Timeout del proveedor logistico: {}", error.getMessage());
        return problema(HttpStatus.GATEWAY_TIMEOUT, "proveedor-timeout", error.getMessage());
    }

    @ExceptionHandler(ProveedorNoDisponibleException.class)
    public Mono<ProblemDetail> proveedorNoDisponible(ProveedorNoDisponibleException error) {
        log.warn("Proveedor logistico no disponible ({})", error.causa());
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "proveedor-no-disponible", error.getMessage());
    }

    @ExceptionHandler({ProveedorHttpException.class, ProveedorRespuestaInvalidaException.class})
    public Mono<ProblemDetail> proveedorError(Exception error) {
        log.warn("Error del proveedor logistico: {}", error.getMessage());
        ProblemDetail detalle = detalle(HttpStatus.BAD_GATEWAY, "proveedor-error", error.getMessage());
        if (error instanceof ProveedorHttpException http) {
            detalle.setProperty("codigoProveedor", http.status());
            detalle.setProperty("intentos", http.intentos());
        }
        return Mono.just(detalle);
    }

    @ExceptionHandler(ClienteServiceNoDisponibleException.class)
    public Mono<ProblemDetail> servicioClientesNoDisponible(ClienteServiceNoDisponibleException error) {
        log.warn("El servicio de clientes no esta disponible: {}", error.getMessage());
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "servicio-clientes-no-disponible", error.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ProblemDetail> peticionInvalida(WebExchangeBindException error) {
        List<Map<String, String>> errores = error.getFieldErrors().stream()
                .map(errorCampo -> Map.of(
                        "campo", errorCampo.getField(),
                        "mensaje", errorCampo.getDefaultMessage() == null ? "valor invalido" : errorCampo.getDefaultMessage()))
                .toList();
        ProblemDetail detalle = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "La peticion no cumple las reglas de validacion");
        detalle.setType(URI.create(TIPO_BASE + "peticion-invalida"));
        detalle.setTitle("Peticion invalida");
        detalle.setProperty("errores", errores);
        return Mono.just(detalle);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ProblemDetail> errorDeEstado(ResponseStatusException error) {
        HttpStatus estado = HttpStatus.resolve(error.getStatusCode().value());
        return problema(estado == null ? HttpStatus.INTERNAL_SERVER_ERROR : estado,
                "error", error.getReason() == null ? "Error en la solicitud" : error.getReason());
    }

    @ExceptionHandler(Exception.class)
    public Mono<ProblemDetail> errorInesperado(Exception error) {
        log.error("Error inesperado en el servicio de pedidos", error);
        // No se filtra el detalle interno al cliente: se registra y se devuelve un 500 generico.
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "error-interno",
                "Se ha producido un error inesperado. Contacte con soporte si el problema persiste.");
    }

    private Mono<ProblemDetail> problema(HttpStatus estado, String tipo, String detalle) {
        return Mono.just(detalle(estado, tipo, detalle));
    }

    private static ProblemDetail detalle(HttpStatus estado, String tipo, String detalle) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(estado, detalle);
        problemDetail.setType(URI.create(TIPO_BASE + tipo));
        problemDetail.setTitle(estado.getReasonPhrase());
        return problemDetail;
    }
}
