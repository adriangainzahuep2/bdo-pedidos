package com.bdo.pedidos.api.persistencia;

import com.bdo.pedidos.dominio.modelo.EstadoPedido;
import com.bdo.pedidos.dominio.modelo.IntentoConfirmacion;
import com.bdo.pedidos.dominio.modelo.LineaPedido;
import com.bdo.pedidos.dominio.modelo.Pedido;
import com.bdo.pedidos.dominio.puerto.PedidoRepositoryPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Repositorio de pedidos sobre R2DBC.
 *
 * <p>Por que repositorios escritos a mano y no {@code ReactiveCrudRepository}:
 * <ul>
 *   <li><b>H2 no soporta {@code INSERT ... RETURNING}</b> (comprobado: error de sintaxis), y
 *       {@code save()} de Spring Data R2DBC anade la clausula {@code RETURNING} al insertar
 *       entidades con identificador autogenerado. Como el identificador se genera con una
 *       secuencia de la base de datos, se usa un {@code INSERT} explicito.</li>
 *   <li>La transicion de estado necesita un {@code UPDATE} condicional
 *       ({@code WHERE estado = ? AND version = ?}) que ninguna derivacion de Spring Data
 *       expresa de forma natural.</li>
 *   <li>El SQL queda explicito y es identico en H2 y PostgreSQL.</li>
 * </ul>
 */
@Repository
public class PedidoRepository implements PedidoRepositoryPort {

    private static final String SQL_INSERTAR_PEDIDO = """
            INSERT INTO pedido (
                id, cliente_id, cliente_nombre, cliente_email, estado, fecha_creacion,
                fecha_confirmacion, fecha_inicio_confirmacion, importe_productos, coste_envio,
                importe_total, peso_total_kg, num_bultos, codigo_postal_destino, plazo_envio_dias,
                moneda, version, num_intentos_confirmacion, ultimo_error
            ) VALUES (
                :id, :clienteId, :clienteNombre, :clienteEmail, :estado, :fechaCreacion,
                NULL, NULL, :importeProductos, NULL, NULL, :pesoTotalKg, :numBultos,
                :codigoPostalDestino, NULL, 'EUR', 0, 0, NULL
            )
            """;

    private static final String SQL_INSERTAR_LINEA = """
            INSERT INTO pedido_linea (id, pedido_id, producto, cantidad, precio_unitario,
                                      peso_unitario_kg, importe_linea)
            VALUES (nextval('pedido_linea_id_seq'), :pedidoId, :producto, :cantidad,
                    :precioUnitario, :pesoUnitarioKg, :importeLinea)
            """;

    /**
     * Transicion de estado atomica (compare-and-set). Solo escribe si el pedido sigue en
     * {@code estadoEsperado} y con la misma version: es la garantia de que dos
     * confirmaciones simultaneas no provoquen dos llamadas al proveedor.
     */
    private static final String SQL_ACTUALIZAR_SI_ESTADO_ES = """
            UPDATE pedido SET
                estado = :estado,
                coste_envio = :costeEnvio,
                importe_total = :importeTotal,
                plazo_envio_dias = :plazoEnvioDias,
                fecha_confirmacion = :fechaConfirmacion,
                fecha_inicio_confirmacion = :fechaInicioConfirmacion,
                ultimo_error = :ultimoError,
                num_intentos_confirmacion = :numIntentos,
                version = version + 1
            WHERE id = :id AND estado = :estadoEsperado AND version = :versionEsperada
            """;

    private static final String SQL_RECUPERAR_ABANDONADOS = """
            UPDATE pedido SET
                estado = 'PENDIENTE',
                ultimo_error = :motivo,
                fecha_inicio_confirmacion = NULL,
                num_intentos_confirmacion = num_intentos_confirmacion + 1,
                version = version + 1
            WHERE estado = 'CONFIRMANDO' AND fecha_inicio_confirmacion < :limite
            """;

    private static final String SQL_INSERTAR_INTENTO = """
            INSERT INTO pedido_confirmacion_intento (
                id, pedido_id, resultado, codigo_error, mensaje, coste_envio,
                intentos_proveedor, duracion_ms, fecha
            ) VALUES (
                nextval('pedido_confirmacion_intento_id_seq'), :pedidoId, :resultado, :codigoError,
                :mensaje, :costeEnvio, :intentos, :duracionMs, :fecha
            )
            """;

    private static final String SQL_BUSCAR_PEDIDO = """
            SELECT id, cliente_id, cliente_nombre, cliente_email, estado, fecha_creacion,
                   fecha_confirmacion, fecha_inicio_confirmacion, importe_productos,
                   coste_envio, importe_total, peso_total_kg, num_bultos,
                   codigo_postal_destino, plazo_envio_dias, version,
                   num_intentos_confirmacion, ultimo_error
              FROM pedido
             WHERE id = :id
            """;

    private static final String SQL_BUSCAR_LINEAS = """
            SELECT id, pedido_id, producto, cantidad, precio_unitario, peso_unitario_kg, importe_linea
              FROM pedido_linea
             WHERE pedido_id = :pedidoId
             ORDER BY id
            """;

    private final DatabaseClient databaseClient;

    public PedidoRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Pedido> insertar(Pedido pedido) {
        return siguienteIdPedido()
                .flatMap(id -> insertarPedido(id, pedido)
                        .then(insertarLineas(id, pedido.lineas()))
                        .thenReturn(PedidoMapper.conIdentificador(pedido, id)));
    }

    private Mono<Long> insertarPedido(long id, Pedido pedido) {
        return databaseClient.sql(SQL_INSERTAR_PEDIDO)
                .bind("id", id)
                .bind("clienteId", pedido.clienteId())
                .bind("clienteNombre", pedido.clienteNombre())
                .bind("clienteEmail", pedido.clienteEmail())
                .bind("estado", pedido.estado().name())
                .bind("fechaCreacion", PedidoMapper.aTimestamp(pedido.fechaCreacion()))
                .bind("importeProductos", pedido.importeProductos().importe())
                .bind("pesoTotalKg", pedido.pesoTotalKg())
                .bind("numBultos", pedido.numBultos())
                .bind("codigoPostalDestino", pedido.codigoPostalDestino())
                .fetch().rowsUpdated();
    }

    private Mono<Long> insertarLineas(long pedidoId, List<LineaPedido> lineas) {
        return Flux.fromIterable(lineas)
                .concatMap(linea -> databaseClient.sql(SQL_INSERTAR_LINEA)
                        .bind("pedidoId", pedidoId)
                        .bind("producto", linea.producto())
                        .bind("cantidad", linea.cantidad())
                        .bind("precioUnitario", linea.precioUnitario().importe())
                        .bind("pesoUnitarioKg", linea.pesoUnitarioKg())
                        .bind("importeLinea", linea.importeLinea().importe())
                        .fetch().rowsUpdated())
                .reduce(0L, Long::sum);
    }

    /**
     * Lee el pedido y despues sus lineas.
     *
     * <p>La fila se lee <b>dentro</b> de la funcion de mapeo: en R2DBC un {@code Row} solo es
     * valido mientras dura ese callback (el driver de PostgreSQL libera sus buffers al
     * terminar), asi que nunca se deja escapar. Las dos consultas son secuenciales para no
     * solapar dos consultas en la misma conexion cuando se ejecuta dentro de una transaccion.
     */
    @Override
    public Mono<Pedido> buscarPorId(long id) {
        return databaseClient.sql(SQL_BUSCAR_PEDIDO)
                .bind("id", id)
                .map((fila, meta) -> PedidoMapper.aBuilder(fila))
                .one()
                .flatMap(pedido -> lineasDe(id).map(lineas -> pedido.lineas(lineas).build()));
    }

    @Override
    public Mono<Boolean> actualizarSiEstadoEs(long id, long versionEsperada, EstadoPedido estadoEsperado, Pedido pedido) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(SQL_ACTUALIZAR_SI_ESTADO_ES)
                .bind("id", id)
                .bind("estadoEsperado", estadoEsperado.name())
                .bind("versionEsperada", versionEsperada)
                .bind("estado", pedido.estado().name())
                .bind("numIntentos", pedido.numeroIntentosConfirmacion());

        spec = pedido.costeEnvio() == null
                ? spec.bindNull("costeEnvio", BigDecimal.class)
                : spec.bind("costeEnvio", pedido.costeEnvio().importe());
        spec = pedido.importeTotal() == null
                ? spec.bindNull("importeTotal", BigDecimal.class)
                : spec.bind("importeTotal", pedido.importeTotal().importe());
        spec = pedido.plazoEnvioDias() == null
                ? spec.bindNull("plazoEnvioDias", Integer.class)
                : spec.bind("plazoEnvioDias", pedido.plazoEnvioDias());
        spec = pedido.fechaConfirmacion() == null
                ? spec.bindNull("fechaConfirmacion", OffsetDateTime.class)
                : spec.bind("fechaConfirmacion", PedidoMapper.aTimestamp(pedido.fechaConfirmacion()));
        spec = pedido.fechaInicioConfirmacion() == null
                ? spec.bindNull("fechaInicioConfirmacion", OffsetDateTime.class)
                : spec.bind("fechaInicioConfirmacion", PedidoMapper.aTimestamp(pedido.fechaInicioConfirmacion()));
        spec = pedido.ultimoError() == null
                ? spec.bindNull("ultimoError", String.class)
                : spec.bind("ultimoError", pedido.ultimoError());

        return spec.fetch().rowsUpdated().map(filas -> filas > 0);
    }

    @Override
    public Mono<Void> registrarIntento(IntentoConfirmacion intento) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(SQL_INSERTAR_INTENTO)
                .bind("pedidoId", intento.pedidoId())
                .bind("resultado", intento.resultado().name())
                .bind("intentos", intento.intentosProveedor())
                .bind("duracionMs", intento.duracionMs())
                .bind("fecha", PedidoMapper.aTimestamp(intento.fecha()));

        spec = intento.codigoError() == null
                ? spec.bindNull("codigoError", String.class)
                : spec.bind("codigoError", intento.codigoError());
        spec = intento.mensaje() == null
                ? spec.bindNull("mensaje", String.class)
                : spec.bind("mensaje", intento.mensaje());
        spec = intento.costeEnvio() == null
                ? spec.bindNull("costeEnvio", BigDecimal.class)
                : spec.bind("costeEnvio", intento.costeEnvio().importe());

        return spec.fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Integer> recuperarConfirmacionesAbandonadas(Instant limite) {
        return databaseClient.sql(SQL_RECUPERAR_ABANDONADOS)
                .bind("motivo", "Confirmacion abandonada: el proceso no finalizo la llamada al proveedor")
                .bind("limite", PedidoMapper.aTimestamp(limite))
                .fetch().rowsUpdated()
                .map(filas -> filas.intValue());
    }

    /** @return las lineas de un pedido, ordenadas. */
    public Mono<List<LineaPedido>> lineasDe(long pedidoId) {
        return databaseClient.sql(SQL_BUSCAR_LINEAS)
                .bind("pedidoId", pedidoId)
                .map((fila, meta) -> PedidoMapper.aLinea(fila))
                .all()
                .collectList();
    }

    /**
     * Obtiene el siguiente valor de la secuencia de pedidos. Se usa {@code nextval('secuencia')},
     * sintaxis compatible con PostgreSQL y con H2 en modo PostgreSQL.
     */
    private Mono<Long> siguienteIdPedido() {
        return databaseClient.sql("SELECT nextval('pedido_id_seq')")
                .map((fila, meta) -> fila.get(0, Long.class))
                .one();
    }
}
