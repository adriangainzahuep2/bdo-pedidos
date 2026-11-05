package com.bdo.pedidos.api.aplicacion;

import com.bdo.pedidos.dominio.error.ClienteNoEncontradoException;
import com.bdo.pedidos.dominio.error.ConfirmacionEnCursoException;
import com.bdo.pedidos.dominio.error.PedidoNoEncontradoException;
import com.bdo.pedidos.dominio.error.ProveedorHttpException;
import com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException;
import com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException;
import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import com.bdo.pedidos.dominio.modelo.Cliente;
import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.IntentoConfirmacion;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;
import com.bdo.pedidos.dominio.modelo.ResultadoTarifa;
import com.bdo.pedidos.dominio.modelo.SolicitudTarifa;
import com.bdo.pedidos.dominio.modelo.Tarifa;
import com.bdo.pedidos.dominio.puerto.ClientePort;
import com.bdo.pedidos.dominio.puerto.PedidoRepositoryPort;
import com.bdo.pedidos.dominio.puerto.ProveedorTarifasPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios del servicio de aplicacion: sin base de datos y sin HTTP.
 * Comprueban la <b>orquestacion</b> (que se hace y en que orden), no la infraestructura.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PedidoService - orquestacion del caso de uso")
class PedidoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-03T10:00:00Z");

    @Mock
    private PedidoRepositoryPort pedidos;

    @Mock
    private ClientePort clientes;

    @Mock
    private ProveedorTarifasPort proveedor;

    @Mock
    private TransactionalOperator tx;

    private PedidoService servicio;

    @BeforeEach
    void preparar() {
        org.mockito.Mockito.lenient()
                .when(tx.transactional(org.mockito.ArgumentMatchers.<Mono<Pedido>>any()))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        servicio = new PedidoService(pedidos, clientes, proveedor, tx, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    /**
     * Pedido en estado CONFIRMANDO tal y como lo devuelve la base de datos: la version se ha
     * incrementado (el UPDATE condicional hace {@code version = version + 1}).
     */
    private static Pedido pedidoConfirmando(long version) {
        return Pedido.builder()
                .id(1L)
                .clienteId(7L)
                .clienteNombre("Ana Lopez")
                .clienteEmail("ana@example.com")
                .lineas(List.of(linea()))
                .codigoPostalDestino("43840")
                .estado(EstadoPedido.CONFIRMANDO)
                .fechaCreacion(AHORA)
                .fechaInicioConfirmacion(AHORA)
                .version(version)
                .build();
    }

    /** Pedido ya persistido (con identificador y version), tal y como lo devuelve el repositorio. */
    private static Pedido pedidoPendiente() {
        return Pedido.builder()
                .id(1L)
                .clienteId(7L)
                .clienteNombre("Ana Lopez")
                .clienteEmail("ana@example.com")
                .lineas(List.of(linea()))
                .codigoPostalDestino("43840")
                .estado(EstadoPedido.PENDIENTE)
                .fechaCreacion(AHORA)
                .version(0L)
                .build();
    }

    private static LineaPedido linea() {
        return new LineaPedido("Teclado", 2,
                Dinero.precioUnitario(new BigDecimal("45.9900"), "EUR"), new BigDecimal("0.800"));
    }

    private static ResultadoTarifa tarifa() {
        return new ResultadoTarifa(new Tarifa(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2), 1, 120L);
    }

    /** Primera lectura: PENDIENTE; las siguientes: CONFIRMANDO (version 1). */
    private void pedidoQuePasaAConfirmando() {
        when(pedidos.buscarPorId(1L))
                .thenReturn(Mono.just(pedidoPendiente()))
                .thenReturn(Mono.just(pedidoConfirmando(1L)));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(0L), eq(EstadoPedido.PENDIENTE), any()))
                .thenReturn(Mono.just(true));
    }

    @Test
    @DisplayName("crear: valida el cliente, calcula los importes en el servidor y persiste en transaccion")
    void crear() {
        when(clientes.buscarPorId(7L)).thenReturn(Mono.just(new Cliente(7L, "Ana Lopez", "ana@example.com")));
        when(pedidos.insertar(any())).thenAnswer(invocacion -> Mono.just(invocacion.getArgument(0)));

        CrearPedidoCommand orden = new CrearPedidoCommand(7L, "43840", null, List.of(linea()));

        StepVerifier.create(servicio.crear(orden))
                .assertNext(pedido -> {
                    assertThat(pedido.importeProductos().importe().toPlainString()).isEqualTo("91.98");
                    assertThat(pedido.estado()).isEqualTo(EstadoPedido.PENDIENTE);
                    assertThat(pedido.importeTotal()).isNull();
                    assertThat(pedido.clienteNombre()).isEqualTo("Ana Lopez");
                })
                .verifyComplete();

        verify(tx).transactional(org.mockito.ArgumentMatchers.<Mono<Pedido>>any());
    }

    @Test
    @DisplayName("crear: si el cliente no existe, no se crea el pedido")
    void crearConClienteInexistente() {
        when(clientes.buscarPorId(99L)).thenReturn(Mono.empty());

        CrearPedidoCommand orden = new CrearPedidoCommand(99L, "43840", null, List.of(linea()));

        StepVerifier.create(servicio.crear(orden))
                .expectError(ClienteNoEncontradoException.class)
                .verify();

        verify(pedidos, never()).insertar(any());
    }

    @Test
    @DisplayName("confirmar: toma el pedido, llama al proveedor y persiste el total")
    void confirmar() {
        pedidoQuePasaAConfirmando();
        when(proveedor.calcularTarifa(any())).thenReturn(Mono.just(tarifa()));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), any()))
                .thenReturn(Mono.just(true));
        when(pedidos.registrarIntento(any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.confirmar(1L))
                .assertNext(confirmado -> {
                    assertThat(confirmado.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
                    assertThat(confirmado.costeEnvio().importe().toPlainString()).isEqualTo("7.95");
                    assertThat(confirmado.importeTotal().importe().toPlainString()).isEqualTo("99.93");
                })
                .verifyComplete();

        ArgumentCaptor<SolicitudTarifa> solicitud = ArgumentCaptor.forClass(SolicitudTarifa.class);
        verify(proveedor).calcularTarifa(solicitud.capture());
        assertThat(solicitud.getValue().codigoPostalDestino()).isEqualTo("43840");
        assertThat(solicitud.getValue().pesoKg().toPlainString()).isEqualTo("1.600");
        assertThat(solicitud.getValue().numBultos()).isEqualTo(2);

        ArgumentCaptor<IntentoConfirmacion> intento = ArgumentCaptor.forClass(IntentoConfirmacion.class);
        verify(pedidos).registrarIntento(intento.capture());
        assertThat(intento.getValue().resultado()).isEqualTo(IntentoConfirmacion.Resultado.OK);
    }

    @Test
    @DisplayName("confirmar: si otro se ha adelantado, se devuelve 409 y NO se llama al proveedor")
    void confirmarConConflicto() {
        when(pedidos.buscarPorId(1L))
                .thenReturn(Mono.just(pedidoPendiente()))
                .thenReturn(Mono.just(pedidoConfirmando(1L)));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(0L), eq(EstadoPedido.PENDIENTE), any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(servicio.confirmar(1L))
                .expectError(ConfirmacionEnCursoException.class)
                .verify();

        verify(proveedor, never()).calcularTarifa(any());
    }

    @Test
    @DisplayName("confirmar: si el pedido ya esta en CONFIRMANDO, 409 sin intentar el UPDATE ni llamar al proveedor")
    void confirmarPedidoEnCurso() {
        when(pedidos.buscarPorId(1L)).thenReturn(Mono.just(pedidoConfirmando(1L)));

        StepVerifier.create(servicio.confirmar(1L))
                .expectError(ConfirmacionEnCursoException.class)
                .verify();

        verify(pedidos, never()).actualizarSiEstadoEs(anyLong(), anyLong(), any(), any());
        verify(proveedor, never()).calcularTarifa(any());
    }

    @Test
    @DisplayName("confirmar: un pedido ya confirmado es idempotente (no se vuelve a llamar al proveedor)")
    void confirmarPedidoYaConfirmado() {
        Pedido confirmado = pedidoPendiente().marcarConfirmando(AHORA)
                .confirmar(Dinero.importe(new BigDecimal("7.95"), "EUR"), 2, AHORA);
        when(pedidos.buscarPorId(1L)).thenReturn(Mono.just(confirmado));

        StepVerifier.create(servicio.confirmar(1L))
                .assertNext(resultado -> assertThat(resultado.estado()).isEqualTo(EstadoPedido.CONFIRMADO))
                .verifyComplete();

        verify(proveedor, never()).calcularTarifa(any());
        verify(pedidos, never()).actualizarSiEstadoEs(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("confirmar: un pedido cancelado no se puede confirmar (409)")
    void confirmarPedidoCancelado() {
        when(pedidos.buscarPorId(1L)).thenReturn(Mono.just(pedidoPendiente().cancelar(AHORA)));

        StepVerifier.create(servicio.confirmar(1L))
                .expectError(TransicionDeEstadoInvalidaException.class)
                .verify();

        verify(proveedor, never()).calcularTarifa(any());
    }

    @Test
    @DisplayName("confirmar: si el proveedor falla, el pedido vuelve a PENDIENTE y se deja traza del intento")
    void confirmarConFalloDelProveedor() {
        pedidoQuePasaAConfirmando();
        when(proveedor.calcularTarifa(any())).thenReturn(Mono.error(
                new ProveedorHttpException(503, null, "servicio no disponible", 3, "HTTP 503")));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), any()))
                .thenReturn(Mono.just(true));
        when(pedidos.registrarIntento(any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.confirmar(1L))
                .expectError(ProveedorHttpException.class)
                .verify();

        ArgumentCaptor<Pedido> revertido = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidos).actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), revertido.capture());
        assertThat(revertido.getValue().estado()).isEqualTo(EstadoPedido.PENDIENTE);
        assertThat(revertido.getValue().costeEnvio()).isNull();
        assertThat(revertido.getValue().importeTotal()).isNull();
        assertThat(revertido.getValue().ultimoError()).contains("503");

        ArgumentCaptor<IntentoConfirmacion> intento = ArgumentCaptor.forClass(IntentoConfirmacion.class);
        verify(pedidos).registrarIntento(intento.capture());
        assertThat(intento.getValue().resultado()).isEqualTo(IntentoConfirmacion.Resultado.ERROR);
        assertThat(intento.getValue().codigoError()).isEqualTo("HTTP_503");
        assertThat(intento.getValue().intentosProveedor()).isEqualTo(3);
    }

    @Test
    @DisplayName("confirmar: con el circuito abierto (0 llamadas) el pedido vuelve a PENDIENTE y se registra el intento")
    void confirmarConCircuitoAbierto() {
        pedidoQuePasaAConfirmando();
        when(proveedor.calcularTarifa(any())).thenReturn(Mono.error(
                new ProveedorNoDisponibleException("CIRCUITO_ABIERTO", "circuito abierto", 0)));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), any()))
                .thenReturn(Mono.just(true));
        when(pedidos.registrarIntento(any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.confirmar(1L))
                .expectError(ProveedorNoDisponibleException.class)
                .verify();

        ArgumentCaptor<IntentoConfirmacion> intento = ArgumentCaptor.forClass(IntentoConfirmacion.class);
        verify(pedidos).registrarIntento(intento.capture());
        assertThat(intento.getValue().intentosProveedor()).isZero();
        assertThat(intento.getValue().codigoError()).isEqualTo("CIRCUITO_ABIERTO");
    }

    @Test
    @DisplayName("confirmar: si el proveedor no devuelve tarifa, es un error del proveedor y el pedido vuelve a PENDIENTE")
    void confirmarSinTarifa() {
        pedidoQuePasaAConfirmando();
        when(proveedor.calcularTarifa(any())).thenReturn(Mono.empty());
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), any()))
                .thenReturn(Mono.just(true));
        when(pedidos.registrarIntento(any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.confirmar(1L))
                .expectError(ProveedorRespuestaInvalidaException.class)
                .verify();

        ArgumentCaptor<Pedido> revertido = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidos).actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), revertido.capture());
        assertThat(revertido.getValue().estado()).isEqualTo(EstadoPedido.PENDIENTE);
    }

    @Test
    @DisplayName("confirmar: si el cliente HTTP cancela a mitad, la confirmacion termina igualmente (no queda en CONFIRMANDO)")
    void confirmarTerminaAunqueSeCanceleLaPeticion() {
        pedidoQuePasaAConfirmando();
        when(proveedor.calcularTarifa(any())).thenReturn(Mono.just(tarifa()).delayElement(Duration.ofMillis(300)));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO), any()))
                .thenReturn(Mono.just(true));
        when(pedidos.registrarIntento(any())).thenReturn(Mono.empty());

        // El suscriptor (la peticion HTTP) se cancela mientras el proveedor esta respondiendo.
        StepVerifier.create(servicio.confirmar(1L))
                .thenAwait(Duration.ofMillis(50))
                .thenCancel()
                .verify();

        // Aun asi, el resultado del proveedor se persiste: el pedido queda CONFIRMADO.
        ArgumentCaptor<Pedido> confirmado = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidos, timeout(5_000)).actualizarSiEstadoEs(eq(1L), eq(1L), eq(EstadoPedido.CONFIRMANDO),
                confirmado.capture());
        assertThat(confirmado.getValue().estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        verify(pedidos, timeout(5_000)).registrarIntento(any());
    }

    @Test
    @DisplayName("confirmar: un pedido inexistente devuelve 404")
    void confirmarPedidoInexistente() {
        when(pedidos.buscarPorId(404L)).thenReturn(Mono.empty());

        StepVerifier.create(servicio.confirmar(404L))
                .expectError(PedidoNoEncontradoException.class)
                .verify();
    }

    @Test
    @DisplayName("cancelar: solo si sigue PENDIENTE; si otro lo ha cambiado, 409")
    void cancelarConCarrera() {
        when(pedidos.buscarPorId(1L)).thenReturn(Mono.just(pedidoPendiente()));
        when(pedidos.actualizarSiEstadoEs(eq(1L), eq(0L), eq(EstadoPedido.PENDIENTE), any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(servicio.cancelar(1L))
                .expectError(TransicionDeEstadoInvalidaException.class)
                .verify();
    }
}
