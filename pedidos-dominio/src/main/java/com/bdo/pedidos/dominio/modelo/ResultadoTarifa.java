package com.bdo.pedidos.dominio.modelo;

/**
 * Resultado de consultar al proveedor logistico: la tarifa y la telemetria de la llamada
 * (cuantos intentos se han hecho y cuanto ha tardado).
 *
 * <p>Se expone como parte del contrato del puerto (y no como un detalle interno del
 * adaptador) porque el registro de auditoria de intentos de confirmacion necesita esos
 * datos: son la evidencia de "cuantas veces se ha llamado al proveedor" y "cuanto ha
 * tardado", que el enunciado pide poder explicar.
 */
public record ResultadoTarifa(Tarifa tarifa, int intentosProveedor, long duracionMs) {

    public ResultadoTarifa {
        if (tarifa == null) {
            throw new com.bdo.pedidos.dominio.error.DatosInvalidosException("El resultado de la tarifa es obligatorio");
        }
        if (intentosProveedor < 1) {
            throw new com.bdo.pedidos.dominio.error.DatosInvalidosException(
                    "Debe registrarse al menos un intento contra el proveedor");
        }
    }
}
