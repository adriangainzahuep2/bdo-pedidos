package com.bdo.pedidos.api.proveedor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Respuesta del proveedor logistico ({@code POST /api/tarifas}).
 * Se ignoran campos desconocidos: el proveedor puede anadir atributos sin romper el cliente.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TarifaRespuesta(BigDecimal coste, String moneda, Integer plazoDias) {
}
