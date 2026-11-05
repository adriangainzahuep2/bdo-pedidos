package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Solicitud de tarifa al proveedor logistico. Es la proyeccion 1:1 del contrato del
 * proveedor externo ({@code POST /api/tarifas}).
 */
public record SolicitudTarifa(String codigoPostalDestino, BigDecimal pesoKg, int numBultos) {

    public SolicitudTarifa {
        if (codigoPostalDestino == null || codigoPostalDestino.isBlank()) {
            throw new DatosInvalidosException("El codigo postal de destino es obligatorio");
        }
        if (pesoKg == null || pesoKg.signum() < 0) {
            throw new DatosInvalidosException("El peso en kg es obligatorio y no puede ser negativo");
        }
        if (numBultos <= 0) {
            throw new DatosInvalidosException("El numero de bultos debe ser mayor que cero");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SolicitudTarifa otra)) {
            return false;
        }
        return codigoPostalDestino.equals(otra.codigoPostalDestino)
                && pesoKg.compareTo(otra.pesoKg) == 0
                && numBultos == otra.numBultos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(codigoPostalDestino, pesoKg.stripTrailingZeros(), numBultos);
    }
}
