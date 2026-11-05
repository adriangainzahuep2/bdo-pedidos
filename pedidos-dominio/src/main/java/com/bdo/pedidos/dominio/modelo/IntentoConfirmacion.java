package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;

import java.time.Instant;

/**
 * Traza de un intento de confirmacion de pedido.
 *
 * <p>Es la evidencia que permite responder "en que estado ha quedado el pedido y por que"
 * sin depender de los logs: cada llamada al proveedor (con exito o con fallo) deja una
 * fila con el resultado, el error, el numero de intentos realizados contra el proveedor
 * (0 si ni siquiera se llego a llamar: circuito abierto o limite saturado) y la duracion total.
 */
public record IntentoConfirmacion(long pedidoId,
                                  Resultado resultado,
                                  String codigoError,
                                  String mensaje,
                                  Dinero costeEnvio,
                                  int intentosProveedor,
                                  long duracionMs,
                                  Instant fecha) {

    /** Resultado del intento de confirmacion. */
    public enum Resultado {
        /** El proveedor respondio correctamente y el pedido quedo CONFIRMADO. */
        OK,
        /** El proveedor respondio con error (por ejemplo 503) y el pedido volvio a PENDIENTE. */
        ERROR,
        /** Se agoto el tiempo disponible y el pedido volvio a PENDIENTE. */
        TIMEOUT
    }

    public IntentoConfirmacion {
        if (pedidoId <= 0) {
            throw new DatosInvalidosException("El identificador de pedido del intento es obligatorio");
        }
        if (resultado == null) {
            throw new DatosInvalidosException("El resultado del intento es obligatorio");
        }
        // 0 es valido: con el circuito abierto o el limite de peticiones saturado la
        // confirmacion falla sin haber llegado a llamar al proveedor.
        if (intentosProveedor < 0) {
            throw new DatosInvalidosException("El numero de intentos contra el proveedor no puede ser negativo");
        }
        if (duracionMs < 0) {
            throw new DatosInvalidosException("La duracion del intento no puede ser negativa");
        }
        if (fecha == null) {
            throw new DatosInvalidosException("La fecha del intento es obligatoria");
        }
        if (mensaje != null && mensaje.length() > 500) {
            mensaje = mensaje.substring(0, 500);
        }
    }

    public static IntentoConfirmacion ok(long pedidoId, Dinero coste, int intentos, long duracionMs, Instant fecha) {
        return new IntentoConfirmacion(pedidoId, Resultado.OK, null, "Confirmacion correcta", coste, intentos, duracionMs, fecha);
    }

    public static IntentoConfirmacion error(long pedidoId, String codigo, String mensaje, int intentos, long duracionMs, Instant fecha) {
        return new IntentoConfirmacion(pedidoId, Resultado.ERROR, codigo, mensaje, null, intentos, duracionMs, fecha);
    }

    public static IntentoConfirmacion timeout(long pedidoId, String mensaje, int intentos, long duracionMs, Instant fecha) {
        return new IntentoConfirmacion(pedidoId, Resultado.TIMEOUT, "TIMEOUT", mensaje, null, intentos, duracionMs, fecha);
    }
}
