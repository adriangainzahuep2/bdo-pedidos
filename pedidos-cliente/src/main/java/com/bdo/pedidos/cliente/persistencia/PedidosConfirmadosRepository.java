package com.bdo.pedidos.cliente.persistencia;

import com.bdo.pedidos.cliente.eventos.PedidoConfirmadoEvento;
import com.bdo.pedidos.cliente.modelo.ResumenPedidos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proyeccion {@code cliente_pedido_confirmado}: los pedidos confirmados de cada cliente,
 * tal y como llegan por el evento PedidoConfirmado.
 */
@Repository
public class PedidosConfirmadosRepository {

    private static final String SQL_REGISTRAR = """
            INSERT INTO cliente_pedido_confirmado
                (pedido_id, cliente_id, importe_total, moneda, fecha_confirmacion, fecha_recepcion)
            VALUES (:pedidoId, :clienteId, :importeTotal, :moneda, :fechaConfirmacion, :fechaRecepcion)
            """;

    private static final String SQL_RESUMEN = """
            SELECT moneda, count(*) AS pedidos, sum(importe_total) AS total
              FROM cliente_pedido_confirmado
             WHERE cliente_id = :clienteId
             GROUP BY moneda
             ORDER BY moneda
            """;

    private final DatabaseClient databaseClient;

    public PedidosConfirmadosRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Insercion idempotente. La entrega de RabbitMQ es "al menos una vez" (un reinicio entre
     * procesar el mensaje y confirmarlo al broker lo vuelve a entregar), asi que un evento
     * repetido no puede contar dos veces: la clave primaria es el pedido y la violacion de
     * esa clave ({@link DuplicateKeyException}) significa "ya registrado". Cualquier otro
     * error de integridad (un cliente desconocido) se propaga para que el binder reintente y,
     * agotados los intentos, mande el mensaje a la cola de mensajes muertos.
     *
     * <p>No se usa {@code ON CONFLICT DO NOTHING}: es SQL de PostgreSQL que H2 no admite, y el
     * mismo repositorio corre sobre los dos.
     *
     * @return {@code true} si el pedido era nuevo; {@code false} si ya estaba (evento repetido).
     */
    public Mono<Boolean> registrar(PedidoConfirmadoEvento evento, Instant recepcion) {
        return databaseClient.sql(SQL_REGISTRAR)
                .bind("pedidoId", evento.pedidoId())
                .bind("clienteId", evento.clienteId())
                .bind("importeTotal", evento.importeTotal())
                .bind("moneda", evento.moneda())
                .bind("fechaConfirmacion", evento.fechaConfirmacion().atOffset(ZoneOffset.UTC))
                .bind("fechaRecepcion", recepcion.atOffset(ZoneOffset.UTC))
                .fetch().rowsUpdated()
                .map(filas -> filas > 0)
                .onErrorResume(DuplicateKeyException.class, repetido -> Mono.just(false));
    }

    public Mono<ResumenPedidos> resumen(long clienteId) {
        return databaseClient.sql(SQL_RESUMEN)
                .bind("clienteId", clienteId)
                .map((fila, meta) -> new Object[]{
                        fila.get("moneda", String.class),
                        fila.get("pedidos", Long.class),
                        fila.get("total", BigDecimal.class)})
                .all()
                .collectList()
                .map(filas -> {
                    long pedidos = 0;
                    Map<String, BigDecimal> importes = new LinkedHashMap<>();
                    for (Object[] fila : filas) {
                        pedidos += (Long) fila[1];
                        importes.put(((String) fila[0]).trim(), (BigDecimal) fila[2]);
                    }
                    return new ResumenPedidos(clienteId, pedidos, importes);
                });
    }
}
