package com.bdo.pedidos.api.proveedor;

import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.Tarifa;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utilidades compartidas por los dos adaptadores del proveedor (reactivo y bloqueante):
 * interpretacion del contrato y de la cabecera {@code Retry-After}.
 */
public final class ProveedorTarifasMapper {

    private ProveedorTarifasMapper() {
    }

    /**
     * Traduce la respuesta del proveedor al modelo de dominio, validando el contrato.
     *
     * @throws ProveedorRespuestaInvalidaException si faltan campos, la moneda no es EUR
     *                                           o el coste es negativo
     */
    public static Tarifa aTarifa(TarifaRespuesta respuesta) {
        if (respuesta == null) {
            throw new ProveedorRespuestaInvalidaException("El proveedor logistico devolvio una respuesta vacia");
        }
        if (respuesta.coste() == null || respuesta.plazoDias() == null) {
            throw new ProveedorRespuestaInvalidaException(
                    "La respuesta del proveedor logistico no incluye 'coste' y 'plazoDias'");
        }
        if (respuesta.moneda() == null || !Dinero.MONEDA_EUR.equalsIgnoreCase(respuesta.moneda().trim())) {
            throw new ProveedorRespuestaInvalidaException(
                    "El proveedor logistico devolvio una moneda no soportada: " + respuesta.moneda());
        }
        // El signo se comprueba ANTES de redondear: -0.001 redondeado a centimos es 0.00 y
        // pasaria por un coste valido.
        if (respuesta.coste().signum() < 0) {
            throw new ProveedorRespuestaInvalidaException(
                    "El proveedor logistico devolvio un coste negativo: " + respuesta.coste().toPlainString());
        }
        // Si el proveedor envia mas de 2 decimales se redondea a centimos (HALF_UP): es el
        // importe que se va a cobrar al cliente.
        BigDecimal coste = respuesta.coste().setScale(Dinero.ESCALA_IMPORTE, RoundingMode.HALF_UP);
        if (respuesta.plazoDias() < 0) {
            throw new ProveedorRespuestaInvalidaException(
                    "El proveedor logistico devolvio un plazo negativo: " + respuesta.plazoDias());
        }
        return new Tarifa(Dinero.importe(coste, Dinero.MONEDA_EUR), respuesta.plazoDias());
    }

    /** Espera maxima que se acepta de {@code Retry-After} (los adaptadores la acotan ademas al presupuesto). */
    static final int RETRY_AFTER_MAXIMO_SEGUNDOS = 60;

    /**
     * Interpreta la cabecera {@code Retry-After}: puede ser un numero de segundos o una
     * fecha HTTP. Devuelve {@code null} si no hay cabecera o no se puede interpretar, y
     * acota el valor a [0, 60] segundos en los dos formatos.
     */
    public static Integer retryAfter(HttpHeaders headers, Clock clock) {
        if (headers == null) {
            return null;
        }
        String valor = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Math.max(0, Math.min(Integer.parseInt(valor.trim()), RETRY_AFTER_MAXIMO_SEGUNDOS));
        } catch (NumberFormatException ignorada) {
            try {
                ZonedDateTime instante = ZonedDateTime.parse(valor.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                long segundos = Duration.between(ZonedDateTime.now(clock), instante).getSeconds();
                return (int) Math.max(0, Math.min(segundos, RETRY_AFTER_MAXIMO_SEGUNDOS));
            } catch (RuntimeException invalida) {
                return null;
            }
        }
    }

    /** Trunca el cuerpo de la respuesta para no arrastrar payloads grandes a los logs. */
    public static String truncar(String cuerpo, int maximo) {
        if (cuerpo == null) {
            return "";
        }
        return cuerpo.length() <= maximo ? cuerpo : cuerpo.substring(0, maximo) + "...";
    }
}
