package com.bdo.pedidos.api.web;

import com.bdo.pedidos.api.aplicacion.CrearPedidoCommand;
import com.bdo.pedidos.api.aplicacion.PedidoService;
import com.bdo.pedidos.api.web.dto.CrearPedidoRequest;
import com.bdo.pedidos.api.web.dto.PedidoResponse;
import com.bdo.pedidos.dominio.error.PedidoNoEncontradoException;
import com.bdo.pedidos.dominio.modelo.Dinero;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;

import java.net.URI;
import java.util.List;

/**
 * API de pedidos.
 *
 * <ul>
 *   <li>{@code POST /api/pedidos} crea un pedido con sus lineas en una sola transaccion
 *       (el importe lo calcula el servidor).</li>
 *   <li>{@code POST /api/pedidos/{id}/confirmacion} confirma el pedido: pide la tarifa al
 *       proveedor externo, la guarda y actualiza el total.</li>
 *   <li>{@code POST /api/pedidos/{id}/cancelacion} cancela un pedido pendiente.</li>
 *   <li>{@code GET /api/pedidos/{id}} consulta un pedido.</li>
 * </ul>
 *
 * <p>Los controladores son "delgados": traducen HTTP &lt;-&gt; dominio y delegan el caso de
 * uso en {@link PedidoService}. No hay logica de negocio aqui.
 */
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final PedidoService servicio;

    public PedidoController(PedidoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public Mono<ResponseEntity<PedidoResponse>> crear(@Valid @RequestBody CrearPedidoRequest peticion) {
        return servicio.crear(new CrearPedidoCommand(
                        peticion.clienteId(),
                        peticion.codigoPostalDestino(),
                        peticion.numBultos(),
                        toLineas(peticion.lineas())))
                .map(pedido -> ResponseEntity
                        .created(URI.create("/api/pedidos/" + pedido.id()))
                        .body(PedidoResponse.desde(pedido)));
    }

    @PostMapping("/{id}/confirmacion")
    public Mono<ResponseEntity<PedidoResponse>> confirmar(@PathVariable long id) {
        return servicio.confirmar(id)
                .map(pedido -> ResponseEntity.ok(PedidoResponse.desde(pedido)));
    }

    @PostMapping("/{id}/cancelacion")
    public Mono<ResponseEntity<PedidoResponse>> cancelar(@PathVariable long id) {
        return servicio.cancelar(id)
                .map(pedido -> ResponseEntity.ok(PedidoResponse.desde(pedido)));
    }

    @GetMapping("/{id}")
    public Mono<PedidoResponse> obtener(@PathVariable long id) {
        return servicio.obtener(id)
                .switchIfEmpty(Mono.error(new PedidoNoEncontradoException(id)))
                .map(PedidoResponse::desde);
    }

    private List<LineaPedido> toLineas(List<CrearPedidoRequest.LineaPedidoRequest> lineas) {
        if (lineas == null) {
            return List.of();
        }
        return lineas.stream()
                .map(linea -> new LineaPedido(
                        linea.producto(),
                        linea.cantidad(),
                        Dinero.precioUnitario(linea.precioUnitario(), "EUR"),
                        linea.pesoUnitarioKg()))
                .toList();
    }
}
