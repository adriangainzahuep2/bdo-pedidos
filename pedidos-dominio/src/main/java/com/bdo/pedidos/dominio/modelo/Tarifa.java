package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;

/**
 * Tarifa devuelta por el proveedor logistico: coste, moneda y plazo en dias.
 * La validacion de la moneda (que debe ser EUR) la hace el adaptador, porque es un
 * detalle del contrato con el proveedor y no del dominio.
 */
public record Tarifa(Dinero coste, int plazoDias) {

    public Tarifa {
        if (coste == null) {
            throw new DatosInvalidosException("La tarifa debe incluir un coste");
        }
        if (plazoDias < 0) {
            throw new DatosInvalidosException("El plazo en dias no puede ser negativo");
        }
    }
}
