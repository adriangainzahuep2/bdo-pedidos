package com.bdo.pedidos.cliente.persistencia;

import com.bdo.pedidos.cliente.modelo.Cliente;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Repositorio de clientes sobre R2DBC (mismo enfoque que en el servicio de pedidos:
 * repositorio escrito a mano, SQL explicito y portable entre H2 y PostgreSQL).
 */
@Repository
public class ClienteRepository {

    private static final String SQL_INSERTAR = """
            INSERT INTO cliente (id, nombre, email, fecha_creacion)
            VALUES (:id, :nombre, :email, :fechaCreacion)
            """;

    private static final String SQL_BUSCAR = """
            SELECT id, nombre, email, fecha_creacion
              FROM cliente
             WHERE id = :id
            """;

    private static final String SQL_BUSCAR_POR_EMAIL = """
            SELECT id, nombre, email, fecha_creacion
              FROM cliente
             WHERE email = :email
            """;

    private final DatabaseClient databaseClient;

    public ClienteRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Inserta un cliente.
     *
     * <p>El identificador se reserva con la secuencia <b>antes</b> del INSERT y se inserta
     * de forma explicita: si el INSERT usara {@code nextval(...)} en la propia sentencia, la
     * secuencia consumiria un valor distinto del reservado y el cliente devolveria un id
     * que no corresponde con la fila insertada.
     */
    public Mono<Cliente> insertar(String nombre, String email, Instant ahora) {
        return siguienteId()
                .flatMap(id -> databaseClient.sql(SQL_INSERTAR)
                        .bind("id", id)
                        .bind("nombre", nombre)
                        .bind("email", email)
                        .bind("fechaCreacion", ahora.atOffset(ZoneOffset.UTC))
                        .fetch().rowsUpdated()
                        .thenReturn(new Cliente(id, nombre, email, ahora)));
    }

    public Mono<Cliente> buscarPorId(long id) {
        return databaseClient.sql(SQL_BUSCAR)
                .bind("id", id)
                .map((fila, meta) -> aCliente(fila))
                .one();
    }

    public Mono<Cliente> buscarPorEmail(String email) {
        return databaseClient.sql(SQL_BUSCAR_POR_EMAIL)
                .bind("email", email)
                .map((fila, meta) -> aCliente(fila))
                .one();
    }

    private Mono<Long> siguienteId() {
        return databaseClient.sql("SELECT nextval('cliente_id_seq')")
                .map((fila, meta) -> fila.get(0, Long.class))
                .one();
    }

    private Cliente aCliente(io.r2dbc.spi.Row fila) {
        OffsetDateTime fecha = fila.get("fecha_creacion", OffsetDateTime.class);
        return new Cliente(fila.get("id", Long.class),
                fila.get("nombre", String.class),
                fila.get("email", String.class),
                fecha == null ? null : fecha.toInstant());
    }
}
