package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de los objetos de valor del dominio y de sus invariantes.
 *
 * <p>Estos tipos son la frontera del dominio hacia el exterior (el proveedor, la base de
 * datos, la API), asi que sus reglas de construccion se verifican de forma directa: un
 * dato invalido debe fallar al construir el objeto, no despues, en la base de datos.
 */
@DisplayName("Objetos de valor del dominio")
class ValoresDelDominioTest {

    @Nested
    @DisplayName("Tarifa")
    class TarifaTest {

        @Test
        @DisplayName("conserva el coste y el plazo")
        void conservaCosteYPlazo() {
            Tarifa tarifa = new Tarifa(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2);

            assertThat(tarifa.coste().importe()).isEqualByComparingTo("7.95");
            assertThat(tarifa.coste().moneda()).isEqualTo("EUR");
            assertThat(tarifa.plazoDias()).isEqualTo(2);
        }

        @Test
        @DisplayName("exige un coste")
        void exigeCoste() {
            assertThatThrownBy(() -> new Tarifa(null, 2))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("coste");
        }

        @Test
        @DisplayName("no admite un plazo negativo")
        void noAdmitePlazoNegativo() {
            assertThatThrownBy(() -> new Tarifa(Dinero.importe(new BigDecimal("1.00"), "EUR"), -1))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("plazo");
        }

        @Test
        @DisplayName("admite un plazo de cero (envio urgente el mismo dia)")
        void admitePlazoCero() {
            assertThat(new Tarifa(Dinero.importe(new BigDecimal("1.00"), "EUR"), 0).plazoDias()).isZero();
        }
    }

    @Nested
    @DisplayName("ResultadoTarifa")
    class ResultadoTarifaTest {

        @Test
        @DisplayName("conserva la tarifa y la telemetria de la llamada")
        void conservaTelemetria() {
            ResultadoTarifa resultado = new ResultadoTarifa(
                    new Tarifa(Dinero.importe(new BigDecimal("9.98"), "EUR"), 3), 2, 1_250L);

            assertThat(resultado.tarifa().coste().importe()).isEqualByComparingTo("9.98");
            assertThat(resultado.intentosProveedor()).isEqualTo(2);
            assertThat(resultado.duracionMs()).isEqualTo(1_250L);
        }

        @Test
        @DisplayName("exige al menos un intento contra el proveedor")
        void exigeUnIntento() {
            assertThatThrownBy(() -> new ResultadoTarifa(
                    new Tarifa(Dinero.importe(new BigDecimal("9.98"), "EUR"), 3), 0, 10L))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("intento");
        }
    }

    @Nested
    @DisplayName("SolicitudTarifa")
    class SolicitudTarifaTest {

        @Test
        @DisplayName("es la proyeccion 1:1 del contrato del proveedor")
        void respetaElContrato() {
            SolicitudTarifa solicitud = new SolicitudTarifa("43840", new BigDecimal("3.500"), 2);

            assertThat(solicitud.codigoPostalDestino()).isEqualTo("43840");
            assertThat(solicitud.pesoKg()).isEqualByComparingTo("3.5");
            assertThat(solicitud.numBultos()).isEqualTo(2);
        }

        @Test
        @DisplayName("exige codigo postal de destino")
        void exigeCodigoPostal() {
            assertThatThrownBy(() -> new SolicitudTarifa(" ", new BigDecimal("1.0"), 1))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("codigo postal");
        }

        @Test
        @DisplayName("no admite peso negativo")
        void noAdmitePesoNegativo() {
            assertThatThrownBy(() -> new SolicitudTarifa("43840", new BigDecimal("-0.1"), 1))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("peso");
        }

        @Test
        @DisplayName("no admite cero bultos")
        void noAdmiteCeroBultos() {
            assertThatThrownBy(() -> new SolicitudTarifa("43840", new BigDecimal("1.0"), 0))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("bultos");
        }

        @Test
        @DisplayName("compara por valor (no por escala decimal)")
        void comparaPorValor() {
            SolicitudTarifa a = new SolicitudTarifa("43840", new BigDecimal("3.500"), 2);
            SolicitudTarifa b = new SolicitudTarifa("43840", new BigDecimal("3.5"), 2);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    @DisplayName("Cliente")
    class ClienteTest {

        @Test
        @DisplayName("conserva los datos del cliente")
        void conservaDatos() {
            Cliente cliente = new Cliente(7L, "Empresa Demo S.L.", "demo@bdo-es.example");

            assertThat(cliente.id()).isEqualTo(7L);
            assertThat(cliente.nombre()).isEqualTo("Empresa Demo S.L.");
            assertThat(cliente.email()).isEqualTo("demo@bdo-es.example");
        }

        @Test
        @DisplayName("exige identificador positivo, nombre y email")
        void exigeDatosObligatorios() {
            assertThatThrownBy(() -> new Cliente(0L, "Empresa", "a@b.example"))
                    .isInstanceOf(DatosInvalidosException.class);
            assertThatThrownBy(() -> new Cliente(1L, "  ", "a@b.example"))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("nombre");
            assertThatThrownBy(() -> new Cliente(1L, "Empresa", null))
                    .isInstanceOf(DatosInvalidosException.class)
                    .hasMessageContaining("email");
        }
    }

    @Nested
    @DisplayName("IntentoConfirmacion")
    class IntentoConfirmacionTest {

        private final Instant ahora = Instant.parse("2026-11-03T22:50:00Z");

        @Test
        @DisplayName("registra un intento correcto con su coste")
        void registraOk() {
            IntentoConfirmacion intento = IntentoConfirmacion.ok(
                    12L, Dinero.importe(new BigDecimal("9.98"), "EUR"), 2, 1_100L, ahora);

            assertThat(intento.resultado()).isEqualTo(IntentoConfirmacion.Resultado.OK);
            assertThat(intento.costeEnvio().importe()).isEqualByComparingTo("9.98");
            assertThat(intento.intentosProveedor()).isEqualTo(2);
            assertThat(intento.duracionMs()).isEqualTo(1_100L);
            assertThat(intento.codigoError()).isNull();
        }

        @Test
        @DisplayName("admite cero intentos: el circuito abierto rechaza sin llamar al proveedor")
        void admiteCeroIntentos() {
            IntentoConfirmacion intento = IntentoConfirmacion.error(
                    1L, "CIRCUITO_ABIERTO", "circuito abierto", 0, 0L, ahora);

            assertThat(intento.intentosProveedor()).isZero();
            assertThat(intento.resultado()).isEqualTo(IntentoConfirmacion.Resultado.ERROR);
        }

        @Test
        @DisplayName("registra un intento fallido con su codigo de error")
        void registraError() {
            IntentoConfirmacion intento = IntentoConfirmacion.error(
                    12L, "503", "El proveedor respondio con HTTP 503", 3, 900L, ahora);

            assertThat(intento.resultado()).isEqualTo(IntentoConfirmacion.Resultado.ERROR);
            assertThat(intento.codigoError()).isEqualTo("503");
            assertThat(intento.costeEnvio()).isNull();
        }

        @Test
        @DisplayName("registra un timeout con su codigo TIMEOUT")
        void registraTimeout() {
            IntentoConfirmacion intento = IntentoConfirmacion.timeout(
                    12L, "Se agoto el tiempo de espera", 1, 8_000L, ahora);

            assertThat(intento.resultado()).isEqualTo(IntentoConfirmacion.Resultado.TIMEOUT);
            assertThat(intento.codigoError()).isEqualTo("TIMEOUT");
            assertThat(intento.costeEnvio()).isNull();
        }

        @Test
        @DisplayName("trunca el mensaje a 500 caracteres (limite de la columna)")
        void truncaMensajeLargo() {
            String mensaje = "x".repeat(900);

            IntentoConfirmacion intento = IntentoConfirmacion.error(12L, "500", mensaje, 1, 10L, ahora);

            assertThat(intento.mensaje()).hasSize(500);
        }

        @Test
        @DisplayName("exige pedido, resultado, intentos y duracion no negativos y fecha")
        void exigeDatosObligatorios() {
            assertThatThrownBy(() -> new IntentoConfirmacion(
                    0L, IntentoConfirmacion.Resultado.OK, null, "ok", null, 1, 1L, ahora))
                    .isInstanceOf(DatosInvalidosException.class);
            assertThatThrownBy(() -> new IntentoConfirmacion(
                    1L, null, null, "ok", null, 1, 1L, ahora))
                    .isInstanceOf(DatosInvalidosException.class);
            assertThatThrownBy(() -> new IntentoConfirmacion(
                    1L, IntentoConfirmacion.Resultado.OK, null, "ok", null, -1, 1L, ahora))
                    .isInstanceOf(DatosInvalidosException.class);
            assertThatThrownBy(() -> new IntentoConfirmacion(
                    1L, IntentoConfirmacion.Resultado.OK, null, "ok", null, 1, -1L, ahora))
                    .isInstanceOf(DatosInvalidosException.class);
            assertThatThrownBy(() -> new IntentoConfirmacion(
                    1L, IntentoConfirmacion.Resultado.OK, null, "ok", null, 1, 1L, null))
                    .isInstanceOf(DatosInvalidosException.class);
        }
    }
}
