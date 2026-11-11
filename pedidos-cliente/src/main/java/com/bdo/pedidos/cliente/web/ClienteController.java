package com.bdo.pedidos.cliente.web;

import com.bdo.pedidos.cliente.aplicacion.ClienteService;
import com.bdo.pedidos.cliente.modelo.Cliente;
import com.bdo.pedidos.cliente.modelo.ResumenPedidos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * API de clientes.
 *
 * <ul>
 *   <li>{@code POST /api/clientes} da de alta un cliente.</li>
 *   <li>{@code GET /api/clientes/{id}} consulta un cliente (lo usa el servicio de pedidos).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService servicio;

    public ClienteController(ClienteService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public Mono<ResponseEntity<ClienteResponse>> crear(@Valid @RequestBody CrearClienteRequest peticion) {
        return servicio.crear(peticion.nombre(), peticion.email())
                .map(cliente -> ResponseEntity
                        .created(URI.create("/api/clientes/" + cliente.id()))
                        .body(ClienteResponse.desde(cliente)));
    }

    @GetMapping("/{id}")
    public Mono<ClienteResponse> obtener(@PathVariable long id) {
        return servicio.obtener(id).map(ClienteResponse::desde);
    }

    /** Resumen de pedidos confirmados (consistente en diferido: lo alimentan los eventos). */
    @GetMapping("/{id}/pedidos-confirmados")
    public Mono<ResumenPedidos> pedidosConfirmados(@PathVariable long id) {
        return servicio.pedidosConfirmados(id);
    }

    /** Peticion de alta de cliente. */
    public record CrearClienteRequest(

            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 120, message = "El nombre admite 120 caracteres como maximo")
            String nombre,

            @NotBlank(message = "El email es obligatorio")
            @Email(message = "El email no tiene un formato valido")
            @Size(max = 254, message = "El email admite 254 caracteres como maximo")
            String email) {
    }

    /** Respuesta de cliente. */
    public record ClienteResponse(long id, String nombre, String email) {

        static ClienteResponse desde(Cliente cliente) {
            return new ClienteResponse(cliente.id(), cliente.nombre(), cliente.email());
        }
    }
}
