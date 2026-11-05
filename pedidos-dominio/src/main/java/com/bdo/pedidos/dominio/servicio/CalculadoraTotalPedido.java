package com.bdo.pedidos.dominio.servicio;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;
import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.LineaPedido;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Servicio de dominio puro: calcula los importes, el peso y el numero de bultos de un
 * pedido. Sin estado, sin Spring, sin efectos de entrada/salida: por eso se puede probar
 * de forma exhaustiva y rapida con tests unitarios (requisito explicito del enunciado).
 *
 * <p>Regla de redondeo (documentada en README):
 * <ol>
 *   <li>El importe de cada <b>linea</b> se redondea a 2 decimales con HALF_UP.</li>
 *   <li>El importe de productos es la <b>suma de las lineas ya redondeadas</b>. No se redondea
 *       la suma sin redondear lineas: asi el total siempre coincide con la suma de lo que el
 *       cliente ve en el detalle, sin diferencias de centimos.</li>
 *   <li>El importe total es {@code importeProductos + costeEnvio}; ambos a 2 decimales, la
 *       suma es exacta (no hay redondeo implicito).</li>
 * </ol>
 */
public final class CalculadoraTotalPedido {

    private CalculadoraTotalPedido() {
    }

    /** Importe total de los productos del pedido (suma de lineas redondeadas). */
    public static Dinero importeProductos(List<LineaPedido> lineas) {
        validarLineas(lineas);
        Dinero total = Dinero.cero();
        for (LineaPedido linea : lineas) {
            total = total.mas(linea.importeLinea());
        }
        return total;
    }

    /** Peso total del pedido en kg (3 decimales). */
    public static BigDecimal pesoTotalKg(List<LineaPedido> lineas) {
        validarLineas(lineas);
        BigDecimal peso = BigDecimal.ZERO;
        for (LineaPedido linea : lineas) {
            peso = peso.add(linea.pesoTotalKg());
        }
        return peso.setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Numero de bultos. Por defecto se deriva del numero de unidades (un bulto por unidad);
     * el cliente puede declararlo explicitamente en la peticion de creacion.
     */
    public static int numBultos(List<LineaPedido> lineas) {
        validarLineas(lineas);
        int bultos = 0;
        for (LineaPedido linea : lineas) {
            bultos += linea.cantidad();
        }
        return bultos;
    }

    /** Importe total del pedido: productos + coste de envio. */
    public static Dinero importeTotal(Dinero importeProductos, Dinero costeEnvio) {
        if (importeProductos == null || costeEnvio == null) {
            throw new DatosInvalidosException("El importe total necesita el importe de productos y el coste de envio");
        }
        if (!importeProductos.moneda().equalsIgnoreCase(costeEnvio.moneda())) {
            throw new DatosInvalidosException("El coste de envio debe expresarse en la misma moneda que los productos");
        }
        return importeProductos.mas(costeEnvio);
    }

    private static void validarLineas(List<LineaPedido> lineas) {
        if (lineas == null || lineas.isEmpty()) {
            throw new DatosInvalidosException("El pedido debe incluir al menos una linea");
        }
    }
}
