package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;

import java.math.BigDecimal;

/**
 * Linea de pedido: producto, cantidad y precio unitario (y peso unitario, necesario
 * para calcular el peso total que exige el contrato del proveedor logistico).
 *
 * <p>El importe de la linea se calcula siempre en servidor: {@link #importeLinea()} redondea
 * a 2 decimales con HALF_UP. El cliente nunca puede enviar el importe.
 */
public record LineaPedido(String producto, int cantidad, Dinero precioUnitario, BigDecimal pesoUnitarioKg) {

    public LineaPedido {
        if (producto == null || producto.isBlank()) {
            throw new DatosInvalidosException("El producto de la linea es obligatorio");
        }
        if (producto.length() > 200) {
            throw new DatosInvalidosException("El producto de la linea no puede superar los 200 caracteres");
        }
        if (cantidad <= 0) {
            throw new DatosInvalidosException("La cantidad de la linea debe ser mayor que cero");
        }
        if (precioUnitario == null) {
            throw new DatosInvalidosException("El precio unitario de la linea es obligatorio");
        }
        if (precioUnitario.escala() != Dinero.ESCALA_PRECIO_UNITARIO) {
            throw new DatosInvalidosException("El precio unitario debe expresarse con 4 decimales");
        }
        if (pesoUnitarioKg == null) {
            throw new DatosInvalidosException("El peso unitario de la linea es obligatorio");
        }
        if (pesoUnitarioKg.signum() < 0) {
            throw new DatosInvalidosException("El peso unitario no puede ser negativo");
        }
        if (pesoUnitarioKg.scale() > 3) {
            throw new DatosInvalidosException("El peso unitario admite como maximo 3 decimales (gramos)");
        }
    }

    /** Importe de la linea: cantidad x precio unitario, redondeado a centimos (HALF_UP). */
    public Dinero importeLinea() {
        return precioUnitario.porUnidades(cantidad).aImporte();
    }

    /** Peso total de la linea en kg. */
    public BigDecimal pesoTotalKg() {
        return pesoUnitarioKg.multiply(BigDecimal.valueOf(cantidad));
    }
}
