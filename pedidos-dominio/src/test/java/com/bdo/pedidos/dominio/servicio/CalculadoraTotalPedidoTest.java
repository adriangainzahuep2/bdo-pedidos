package com.bdo.pedidos.dominio.servicio;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;
import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CalculadoraTotalPedido - calculo unitario del total")
class CalculadoraTotalPedidoTest {

    private static LineaPedido linea(String producto, int cantidad, String precioUnitario, String pesoUnitarioKg) {
        return new LineaPedido(producto, cantidad,
                Dinero.precioUnitario(new BigDecimal(precioUnitario), "EUR"),
                new BigDecimal(pesoUnitarioKg));
    }

    @Nested
    @DisplayName("importe de productos")
    class ImporteProductos {

        @Test
        @DisplayName("suma las lineas del pedido")
        void sumaLasLineas() {
            List<LineaPedido> lineas = List.of(
                    linea("Teclado", 2, "45.9900", "0.800"),
                    linea("Raton", 1, "19.5000", "0.100"));

            Dinero total = CalculadoraTotalPedido.importeProductos(lineas);

            assertThat(total).isEqualTo(Dinero.importe(new BigDecimal("111.48"), "EUR"));
            assertThat(total.importe().toPlainString()).isEqualTo("111.48");
        }

        @Test
        @DisplayName("una sola linea devuelve su importe")
        void unaSolaLinea() {
            List<LineaPedido> lineas = List.of(linea("Monitor", 3, "199.0000", "4.500"));

            Dinero total = CalculadoraTotalPedido.importeProductos(lineas);

            assertThat(total.importe().toPlainString()).isEqualTo("597.00");
        }

        @Test
        @DisplayName("el importe lo calcula el servidor: ignora cualquier importe enviado por el cliente")
        void elImporteNoVieneDelCliente() {
            // El cliente podria enviar "importeTotal: 0.01"; el dominio solo conoce lineas.
            List<LineaPedido> lineas = List.of(linea("Suscripcion", 12, "9.9900", "0.000"));

            Dinero total = CalculadoraTotalPedido.importeProductos(lineas);

            assertThat(total.importe().toPlainString()).isEqualTo("119.88");
        }

        @Test
        @DisplayName("redondea cada linea a centimos con HALF_UP antes de sumar")
        void redondeaCadaLinea() {
            // 3 x 0.335 = 1.005 -> 1.01 (HALF_UP). 1 x 0.005 = 0.005 -> 0.01 (HALF_UP).
            List<LineaPedido> lineas = List.of(
                    linea("A", 3, "0.3350", "1.000"),
                    linea("B", 1, "0.0050", "1.000"));

            Dinero total = CalculadoraTotalPedido.importeProductos(lineas);

            assertThat(total.importe().toPlainString()).isEqualTo("1.02");
        }

        @Test
        @DisplayName("rechaza un pedido sin lineas")
        void rechazaPedidoSinLineas() {
            assertThatThrownBy(() -> CalculadoraTotalPedido.importeProductos(Collections.emptyList()))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("al menos una linea");
        }
    }

    @Nested
    @DisplayName("peso y bultos")
    class PesoYBultos {

        @Test
        @DisplayName("suma el peso de todas las lineas con 3 decimales")
        void sumaElPeso() {
            List<LineaPedido> lineas = List.of(
                    linea("Teclado", 2, "45.9900", "0.850"),
                    linea("Raton", 3, "19.5000", "0.100"));

            BigDecimal peso = CalculadoraTotalPedido.pesoTotalKg(lineas);

            assertThat(peso.toPlainString()).isEqualTo("2.000");
        }

        @Test
        @DisplayName("el numero de bultos por defecto es el numero de unidades")
        void bultosPorUnidades() {
            List<LineaPedido> lineas = List.of(
                    linea("Teclado", 2, "45.9900", "0.850"),
                    linea("Raton", 3, "19.5000", "0.100"));

            assertThat(CalculadoraTotalPedido.numBultos(lineas)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("importe total")
    class ImporteTotal {

        @Test
        @DisplayName("el total es el importe de productos mas el coste de envio")
        void totalProductosMasEnvio() {
            Dinero productos = Dinero.importe(new BigDecimal("111.48"), "EUR");
            Dinero envio = Dinero.importe(new BigDecimal("7.95"), "EUR");

            Dinero total = CalculadoraTotalPedido.importeTotal(productos, envio);

            assertThat(total.importe().toPlainString()).isEqualTo("119.43");
        }

        @Test
        @DisplayName("la suma de centimos es exacta (no hay perdida de precision)")
        void sumaExacta() {
            Dinero productos = Dinero.importe(new BigDecimal("0.10"), "EUR");
            Dinero envio = Dinero.importe(new BigDecimal("0.20"), "EUR");

            Dinero total = CalculadoraTotalPedido.importeTotal(productos, envio);

            assertThat(total.importe().toPlainString()).isEqualTo("0.30");
        }

        @Test
        @DisplayName("no admite coste de envio en otra moneda")
        void rechazaMonedaDistinta() {
            Dinero productos = Dinero.importe(new BigDecimal("10.00"), "EUR");
            Dinero envio = Dinero.importe(new BigDecimal("1.00"), "USD");

            assertThatThrownBy(() -> CalculadoraTotalPedido.importeTotal(productos, envio))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("misma moneda");
        }
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({
            "2, 10.0000, 20.00",
            "3, 19.9900, 59.97",
            "1, 0.0100, 0.01",
            "7, 1234.5600, 8641.92"
    })
    @DisplayName("importe de linea: cantidad x precio unitario, redondeado a centimos")
    void importeDeLinea(int cantidad, String precio, String esperado) {
        LineaPedido linea = linea("Producto", cantidad, precio, "1.000");

        assertThat(linea.importeLinea().importe().toPlainString()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("varias lineas del mismo producto se suman, no se pisan")
    void lineasDelMismoProducto() {
        List<LineaPedido> lineas = Arrays.asList(
                linea("Cafe", 2, "3.5000", "0.500"),
                linea("Cafe", 1, "3.5000", "0.500"));

        assertThat(CalculadoraTotalPedido.importeProductos(lineas).importe().toPlainString()).isEqualTo("10.50");
    }
}
