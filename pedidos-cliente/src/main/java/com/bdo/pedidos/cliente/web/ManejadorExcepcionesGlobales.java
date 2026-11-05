package com.bdo.pedidos.cliente.web;

import com.bdo.pedidos.cliente.web.error.ClienteNoEncontradoException;
import com.bdo.pedidos.cliente.web.error.EmailDuplicadoException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Traduccion de errores a respuestas HTTP (ProblemDetail, RFC 9457). */
@RestControllerAdvice
public class ManejadorExcepcionesGlobales {

    private static final String TIPO_BASE = "https://bdo.es/clientes/problemas/";

    @ExceptionHandler(ClienteNoEncontradoException.class)
    public Mono<ProblemDetail> noEncontrado(ClienteNoEncontradoException error) {
        return problema(HttpStatus.NOT_FOUND, "cliente-no-encontrado", error.getMessage());
    }

    @ExceptionHandler(EmailDuplicadoException.class)
    public Mono<ProblemDetail> emailDuplicado(EmailDuplicadoException error) {
        return problema(HttpStatus.CONFLICT, "email-duplicado", error.getMessage());
    }

    /**
     * Dos altas simultaneas con el mismo email pasan las dos la comprobacion previa: la
     * restriccion unica de la base de datos rechaza la segunda. Es el mismo conflicto (409),
     * no un error interno.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Mono<ProblemDetail> claveDuplicada(DuplicateKeyException error) {
        return problema(HttpStatus.CONFLICT, "email-duplicado", "Ya existe un cliente con ese email");
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ProblemDetail> peticionInvalida(WebExchangeBindException error) {
        List<Map<String, String>> errores = error.getFieldErrors().stream()
                .map(campo -> Map.of(
                        "campo", campo.getField(),
                        "mensaje", campo.getDefaultMessage() == null ? "valor invalido" : campo.getDefaultMessage()))
                .toList();
        ProblemDetail detalle = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "La peticion no cumple las reglas de validacion");
        detalle.setType(URI.create(TIPO_BASE + "peticion-invalida"));
        detalle.setTitle("Peticion invalida");
        detalle.setProperty("errores", errores);
        return Mono.just(detalle);
    }

    private Mono<ProblemDetail> problema(HttpStatus estado, String tipo, String detalle) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(estado, detalle);
        problemDetail.setType(URI.create(TIPO_BASE + tipo));
        problemDetail.setTitle(estado.getReasonPhrase());
        return Mono.just(problemDetail);
    }
}
