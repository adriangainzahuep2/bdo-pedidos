package com.bdo.pedidos.api.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Peticion de creacion de pedido ({@code POST /api/pedidos}).
 *
 * <p><b>No existe ningun campo para el importe ni para el total.</b> Los importes los
 * calcula el servidor a partir de las lineas: aceptar un importe del cliente seria
 * abrir una puerta a que el cliente fije el precio de su propio pedido.
 */
public record CrearPedidoRequest(

        @NotNull(message = "El cliente es obligatorio")
        @Positive(message = "El identificador de cliente debe ser positivo")
        Long clienteId,

        @NotBlank(message = "El codigo postal de destino es obligatorio")
        @Size(max = 10, message = "El codigo postal de destino admite 10 caracteres como maximo")
        String codigoPostalDestino,

        @Min(value = 1, message = "El numero de bultos debe ser mayor que cero")
        Integer numBultos,

        @NotEmpty(message = "El pedido debe incluir al menos una linea")
        @Size(max = 100, message = "El pedido admite 100 lineas como maximo")
        List<@NotNull(message = "La linea no puede ser nula") @Valid LineaPedidoRequest> lineas) {

    /** Linea de pedido de la peticion. */
    public record LineaPedidoRequest(

            @NotBlank(message = "El producto es obligatorio")
            @Size(max = 200, message = "El producto admite 200 caracteres como maximo")
            String producto,

            @NotNull(message = "La cantidad es obligatoria")
            @Min(value = 1, message = "La cantidad debe ser mayor que cero")
            @Max(value = 10_000, message = "La cantidad admite 10000 unidades como maximo")
            Integer cantidad,

            @NotNull(message = "El precio unitario es obligatorio")
            @DecimalMin(value = "0.00", message = "El precio unitario no puede ser negativo")
            @Digits(integer = 15, fraction = 4, message = "El precio unitario admite 4 decimales")
            BigDecimal precioUnitario,

            @NotNull(message = "El peso unitario es obligatorio")
            @DecimalMin(value = "0.000", message = "El peso unitario no puede ser negativo")
            @Digits(integer = 7, fraction = 3, message = "El peso unitario admite 3 decimales")
            BigDecimal pesoUnitarioKg) {
    }
}
