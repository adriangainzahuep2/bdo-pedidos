package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;
import com.bdo.pedidos.dominio.error.TransicionDeEstadoInvalidaException;
import com.bdo.pedidos.dominio.servicio.CalculadoraTotalPedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Raiz del agregado Pedido.
 *
 * <p>Reglas de negocio que viven aqui y en ningun otro sitio:
 * <ul>
 *   <li>El importe de productos, el peso y el numero de bultos <b>se calculan en servidor</b>
 *       a partir de las lineas: el cliente nunca puede inyectarlos.</li>
 *   <li>Las transiciones de estado solo se pueden ejecutar a traves de los metodos
 *       {@link #marcarConfirmando}, {@link #confirmar}, {@link #devolverAPendiente} y
 *       {@link #cancelar}, que validan contra la maquina de estados ({@link EstadoPedido}).</li>
 *   <li>El importe total solo existe cuando existe coste de envio: mientras el pedido no
 *       esta confirmado, {@code importeTotal} es {@code null}. Un pedido nunca tiene un total
 *       "a medias".</li>
 * </ul>
 *
 * <p>El agregado guarda una <b>instantanea (snapshot) del cliente</b> (nombre y email) en el
 * momento de la creacion. Es una decision deliberada: si el cliente cambia de nombre o de
 * email, el pedido historico debe seguir reflejando con quien se contrato; ademas evita
 * depender del servicio de clientes para poder leer un pedido.
 */
public final class Pedido {

    private final Long id;
    private final long clienteId;
    private final String clienteNombre;
    private final String clienteEmail;
    private final EstadoPedido estado;
    private final Instant fechaCreacion;
    private final Instant fechaConfirmacion;
    private final List<LineaPedido> lineas;
    private final Dinero importeProductos;
    private final Dinero costeEnvio;
    private final Dinero importeTotal;
    private final BigDecimal pesoTotalKg;
    private final int numBultos;
    private final String codigoPostalDestino;
    private final Integer plazoEnvioDias;
    private final long version;
    private final int numeroIntentosConfirmacion;
    private final String ultimoError;
    private final Instant fechaInicioConfirmacion;

    private Pedido(Builder builder) {
        this.id = builder.id;
        this.clienteId = builder.clienteId;
        this.clienteNombre = builder.clienteNombre;
        this.clienteEmail = builder.clienteEmail;
        this.estado = builder.estado;
        this.fechaCreacion = builder.fechaCreacion;
        this.fechaConfirmacion = builder.fechaConfirmacion;
        this.lineas = List.copyOf(builder.lineas);
        this.importeProductos = builder.importeProductos != null
                ? builder.importeProductos
                : CalculadoraTotalPedido.importeProductos(this.lineas);
        this.costeEnvio = builder.costeEnvio;
        this.importeTotal = builder.importeTotal;
        this.pesoTotalKg = builder.pesoTotalKg != null
                ? builder.pesoTotalKg
                : CalculadoraTotalPedido.pesoTotalKg(this.lineas);
        this.numBultos = builder.numBultos != null
                ? builder.numBultos
                : CalculadoraTotalPedido.numBultos(this.lineas);
        this.codigoPostalDestino = builder.codigoPostalDestino;
        this.plazoEnvioDias = builder.plazoEnvioDias;
        this.version = builder.version;
        this.numeroIntentosConfirmacion = builder.numeroIntentosConfirmacion;
        this.ultimoError = builder.ultimoError;
        this.fechaInicioConfirmacion = builder.fechaInicioConfirmacion;
    }

    /**
     * Crea un pedido nuevo en estado {@link EstadoPedido#PENDIENTE}.
     * Los importes, el peso y los bultos se derivan de las lineas.
     *
     * @param numBultosOverride numero de bultos declarado por el cliente; si es {@code null}
     *                           se deriva como la suma de las unidades de las lineas
     */
    public static Pedido crear(long clienteId,
                               String clienteNombre,
                               String clienteEmail,
                               List<LineaPedido> lineas,
                               String codigoPostalDestino,
                               Integer numBultosOverride,
                               Instant ahora) {
        if (lineas == null || lineas.isEmpty()) {
            throw new DatosInvalidosException("El pedido debe incluir al menos una linea");
        }
        return new Pedido(new Builder()
                .clienteId(clienteId)
                .clienteNombre(clienteNombre)
                .clienteEmail(clienteEmail)
                .lineas(lineas)
                .codigoPostalDestino(codigoPostalDestino)
                .numBultos(numBultosOverride)
                .estado(EstadoPedido.PENDIENTE)
                .fechaCreacion(ahora)
                .version(0L)
                .numeroIntentosConfirmacion(0));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Marca el pedido como "en confirmacion". Solo desde {@link EstadoPedido#PENDIENTE}.
     * Este metodo es el que "toma el lock logico": la persistencia lo hara con un UPDATE
     * condicional ({@code WHERE estado = 'PENDIENTE'}), de modo que el estado real en base
     * de datos es el arbitro y no la memoria del proceso.
     */
    public Pedido marcarConfirmando(Instant ahora) {
        exigirEstado(EstadoPedido.PENDIENTE);
        return new Builder(this)
                .estado(EstadoPedido.CONFIRMANDO)
                .fechaInicioConfirmacion(ahora)
                .ultimoError(null)
                .build();
    }

    /**
     * Confirma el pedido con el coste de envio obtenido del proveedor.
     * Solo desde {@link EstadoPedido#CONFIRMANDO}.
     */
    public Pedido confirmar(Dinero costeEnvio, int plazoDias, Instant ahora) {
        exigirEstado(EstadoPedido.CONFIRMANDO);
        if (costeEnvio == null) {
            throw new DatosInvalidosException("El coste de envio no puede ser nulo");
        }
        if (plazoDias < 0) {
            throw new DatosInvalidosException("El plazo de entrega no puede ser negativo");
        }
        return new Builder(this)
                .estado(EstadoPedido.CONFIRMADO)
                .costeEnvio(costeEnvio)
                .plazoEnvioDias(plazoDias)
                .importeTotal(CalculadoraTotalPedido.importeTotal(importeProductos, costeEnvio))
                .fechaConfirmacion(ahora)
                .fechaInicioConfirmacion(null)
                .build();
    }

    /**
     * Devuelve el pedido a {@link EstadoPedido#PENDIENTE} tras un fallo del proveedor.
     * El pedido sigue siendo confirmable (reintentable) y se registra el ultimo error.
     */
    public Pedido devolverAPendiente(String motivo) {
        exigirEstado(EstadoPedido.CONFIRMANDO);
        return new Builder(this)
                .estado(EstadoPedido.PENDIENTE)
                .numeroIntentosConfirmacion(numeroIntentosConfirmacion + 1)
                .ultimoError(motivo)
                .fechaInicioConfirmacion(null)
                .build();
    }

    /** Cancela el pedido. Solo desde {@link EstadoPedido#PENDIENTE}. */
    public Pedido cancelar(Instant ahora) {
        exigirEstado(EstadoPedido.PENDIENTE);
        return new Builder(this)
                .estado(EstadoPedido.CANCELADO)
                .fechaInicioConfirmacion(null)
                .build();
    }

    private void exigirEstado(EstadoPedido esperado) {
        if (estado != esperado) {
            throw new TransicionDeEstadoInvalidaException(
                    "Transicion invalida: el pedido esta en " + estado + " y la operacion requiere " + esperado);
        }
    }

    public Long id() {
        return id;
    }

    public long clienteId() {
        return clienteId;
    }

    public String clienteNombre() {
        return clienteNombre;
    }

    public String clienteEmail() {
        return clienteEmail;
    }

    public EstadoPedido estado() {
        return estado;
    }

    public Instant fechaCreacion() {
        return fechaCreacion;
    }

    public Instant fechaConfirmacion() {
        return fechaConfirmacion;
    }

    public List<LineaPedido> lineas() {
        return lineas;
    }

    public Dinero importeProductos() {
        return importeProductos;
    }

    public Dinero costeEnvio() {
        return costeEnvio;
    }

    public Dinero importeTotal() {
        return importeTotal;
    }

    public BigDecimal pesoTotalKg() {
        return pesoTotalKg;
    }

    public int numBultos() {
        return numBultos;
    }

    public String codigoPostalDestino() {
        return codigoPostalDestino;
    }

    public Integer plazoEnvioDias() {
        return plazoEnvioDias;
    }

    public long version() {
        return version;
    }

    public int numeroIntentosConfirmacion() {
        return numeroIntentosConfirmacion;
    }

    public String ultimoError() {
        return ultimoError;
    }

    public Instant fechaInicioConfirmacion() {
        return fechaInicioConfirmacion;
    }

    @Override
    public String toString() {
        return "Pedido{id=" + id + ", clienteId=" + clienteId + ", estado=" + estado
                + ", importeProductos=" + importeProductos + ", costeEnvio=" + costeEnvio
                + ", importeTotal=" + importeTotal + ", lineas=" + lineas.size() + "}";
    }

    /**
     * Constructor de reconstitucion (lectura desde base de datos) y de las transiciones.
     * Los importes, el peso y los bultos se recalculan siempre a partir de las lineas
     * cuando no se proporcionan, de modo que nunca puede haber divergencia entre
     * el detalle y el total persistido.
     */
    public static final class Builder {

        private Long id;
        private long clienteId;
        private String clienteNombre;
        private String clienteEmail;
        private EstadoPedido estado = EstadoPedido.PENDIENTE;
        private Instant fechaCreacion;
        private Instant fechaConfirmacion;
        private List<LineaPedido> lineas = new ArrayList<>();
        private Dinero importeProductos;
        private Dinero costeEnvio;
        private Dinero importeTotal;
        private BigDecimal pesoTotalKg;
        private Integer numBultos;
        private String codigoPostalDestino;
        private Integer plazoEnvioDias;
        private long version = 0L;
        private int numeroIntentosConfirmacion = 0;
        private String ultimoError;
        private Instant fechaInicioConfirmacion;

        private Builder() {
        }

        private Builder(Pedido origen) {
            this.id = origen.id;
            this.clienteId = origen.clienteId;
            this.clienteNombre = origen.clienteNombre;
            this.clienteEmail = origen.clienteEmail;
            this.estado = origen.estado;
            this.fechaCreacion = origen.fechaCreacion;
            this.fechaConfirmacion = origen.fechaConfirmacion;
            this.lineas = new ArrayList<>(origen.lineas);
            this.importeProductos = origen.importeProductos;
            this.costeEnvio = origen.costeEnvio;
            this.importeTotal = origen.importeTotal;
            this.pesoTotalKg = origen.pesoTotalKg;
            this.numBultos = origen.numBultos;
            this.codigoPostalDestino = origen.codigoPostalDestino;
            this.plazoEnvioDias = origen.plazoEnvioDias;
            this.version = origen.version;
            this.numeroIntentosConfirmacion = origen.numeroIntentosConfirmacion;
            this.ultimoError = origen.ultimoError;
            this.fechaInicioConfirmacion = origen.fechaInicioConfirmacion;
        }

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder clienteId(long clienteId) {
            this.clienteId = clienteId;
            return this;
        }

        public Builder clienteNombre(String clienteNombre) {
            this.clienteNombre = clienteNombre;
            return this;
        }

        public Builder clienteEmail(String clienteEmail) {
            this.clienteEmail = clienteEmail;
            return this;
        }

        public Builder estado(EstadoPedido estado) {
            this.estado = estado;
            return this;
        }

        public Builder fechaCreacion(Instant fechaCreacion) {
            this.fechaCreacion = fechaCreacion;
            return this;
        }

        public Builder fechaConfirmacion(Instant fechaConfirmacion) {
            this.fechaConfirmacion = fechaConfirmacion;
            return this;
        }

        public Builder lineas(List<LineaPedido> lineas) {
            this.lineas = new ArrayList<>(Objects.requireNonNull(lineas, "lineas"));
            return this;
        }

        public Builder importeProductos(Dinero importeProductos) {
            this.importeProductos = importeProductos;
            return this;
        }

        public Builder costeEnvio(Dinero costeEnvio) {
            this.costeEnvio = costeEnvio;
            return this;
        }

        public Builder importeTotal(Dinero importeTotal) {
            this.importeTotal = importeTotal;
            return this;
        }

        public Builder pesoTotalKg(BigDecimal pesoTotalKg) {
            this.pesoTotalKg = pesoTotalKg;
            return this;
        }

        public Builder numBultos(Integer numBultos) {
            this.numBultos = numBultos;
            return this;
        }

        public Builder codigoPostalDestino(String codigoPostalDestino) {
            this.codigoPostalDestino = codigoPostalDestino;
            return this;
        }

        public Builder plazoEnvioDias(Integer plazoEnvioDias) {
            this.plazoEnvioDias = plazoEnvioDias;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder numeroIntentosConfirmacion(int numeroIntentosConfirmacion) {
            this.numeroIntentosConfirmacion = numeroIntentosConfirmacion;
            return this;
        }

        public Builder ultimoError(String ultimoError) {
            this.ultimoError = ultimoError;
            return this;
        }

        public Builder fechaInicioConfirmacion(Instant fechaInicioConfirmacion) {
            this.fechaInicioConfirmacion = fechaInicioConfirmacion;
            return this;
        }

        public Pedido build() {
            if (clienteId <= 0) {
                throw new DatosInvalidosException("El pedido debe estar asociado a un cliente valido");
            }
            if (clienteNombre == null || clienteNombre.isBlank()) {
                throw new DatosInvalidosException("El nombre del cliente es obligatorio");
            }
            if (clienteEmail == null || clienteEmail.isBlank()) {
                throw new DatosInvalidosException("El email del cliente es obligatorio");
            }
            if (lineas.isEmpty()) {
                throw new DatosInvalidosException("El pedido debe incluir al menos una linea");
            }
            if (codigoPostalDestino == null || codigoPostalDestino.isBlank()) {
                throw new DatosInvalidosException("El codigo postal de destino es obligatorio");
            }
            if (estado == null) {
                throw new DatosInvalidosException("El estado del pedido es obligatorio");
            }
            if (importeTotal != null && costeEnvio == null) {
                throw new DatosInvalidosException("No puede existir importe total sin coste de envio");
            }
            return new Pedido(this);
        }
    }
}
