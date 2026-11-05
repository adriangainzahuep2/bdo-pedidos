package com.bdo.pedidos.cliente.aplicacion;

import com.bdo.pedidos.cliente.modelo.Cliente;
import com.bdo.pedidos.cliente.persistencia.ClienteRepository;
import com.bdo.pedidos.cliente.web.error.ClienteNoEncontradoException;
import com.bdo.pedidos.cliente.web.error.EmailDuplicadoException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Caso de uso de clientes: alta y consulta.
 *
 * <p>El email es unico (restriccion del modelo): se comprueba antes de insertar y, aun
 * asi, la base de datos tiene la restriccion unica como red de seguridad (la comprobacion
 * previa sola no evita una carrera; la restriccion si).
 */
@Service
public class ClienteService {

    private final ClienteRepository repositorio;
    private final Clock clock;

    public ClienteService(ClienteRepository repositorio, Clock clock) {
        this.repositorio = repositorio;
        this.clock = clock;
    }

    public Mono<Cliente> crear(String nombre, String email) {
        return repositorio.buscarPorEmail(email)
                .flatMap(existente -> Mono.<Cliente>error(
                        new EmailDuplicadoException("Ya existe un cliente con el email " + email)))
                .switchIfEmpty(Mono.defer(() -> repositorio.insertar(nombre, email, clock.instant())));
    }

    public Mono<Cliente> obtener(long id) {
        return repositorio.buscarPorId(id)
                .switchIfEmpty(Mono.error(new ClienteNoEncontradoException(id)));
    }
}
