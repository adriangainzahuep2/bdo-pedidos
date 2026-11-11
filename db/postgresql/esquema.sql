-- ============================================================================
-- Esquema PostgreSQL completo del sistema de pedidos (PostgreSQL 13+).
--
-- Reune en un solo fichero las tablas de los dos microservicios:
--   * Servicio de clientes : cliente
--   * Servicio de pedidos  : pedido, pedido_linea, pedido_confirmacion_intento
--
-- En el despliegue cada microservicio tiene SU base de datos (docker compose crea
-- "pedidos" y "clientes" con scripts/postgres-init.sh y el DDL de cada servicio).
-- Por eso NO hay clave foranea de pedido.cliente_id a cliente.id: son bases de datos
-- distintas y la integridad se garantiza por la API (el servicio de pedidos valida el
-- cliente por HTTP al crear el pedido y guarda una instantanea de nombre y email).
--
-- Este fichero es equivalente columna a columna a los DDL de los servicios
-- (pedidos-api/.../db/schema.sql y pedidos-cliente/.../db/schema.sql):
-- scripts/probar-postgresql.sh lo comprueba comparando information_schema.
--
-- Decisiones de modelado:
--   * Importes NUMERIC (exactos), nunca DOUBLE PRECISION: precios unitarios con 4
--     decimales, importes con 2 (centimos), pesos con 3 (gramos).
--   * Fechas TIMESTAMP WITH TIME ZONE, guardadas en UTC.
--   * Identificadores con secuencia (nextval): la aplicacion reserva el id antes
--     del INSERT.
--   * "version" es el bloqueo optimista de las transiciones de estado
--     (UPDATE ... WHERE estado = ? AND version = ?).
--   * Las invariantes del pedido viven en el dominio (Java) y, ademas, en CHECK:
--     la base de datos rechaza un pedido inconsistente aunque alguien escriba SQL
--     a mano.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Servicio de CLIENTES
-- ----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS cliente_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS cliente (
    id             BIGINT                   NOT NULL,
    nombre         VARCHAR(120)             NOT NULL,
    email          VARCHAR(254)             NOT NULL,
    fecha_creacion TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_cliente PRIMARY KEY (id),
    CONSTRAINT uk_cliente_email UNIQUE (email)
);

COMMENT ON TABLE cliente IS 'Clientes (dueno: servicio de clientes). El email es la identidad natural.';
COMMENT ON COLUMN cliente.email IS 'Unico: una segunda alta con el mismo email responde 409.';

-- Proyeccion local de los pedidos CONFIRMADOS de cada cliente. La alimenta el evento
-- PedidoConfirmado (RabbitMQ, Spring Cloud Stream): el servicio de clientes no consulta
-- al de pedidos. La clave es el pedido, asi que un mensaje repetido (la entrega es "al
-- menos una vez") no se cuenta dos veces.
CREATE TABLE IF NOT EXISTS cliente_pedido_confirmado (
    pedido_id          BIGINT                   NOT NULL,
    cliente_id         BIGINT                   NOT NULL,
    importe_total      NUMERIC(19, 2)           NOT NULL,
    moneda             CHAR(3)                  NOT NULL,
    fecha_confirmacion TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_recepcion    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_cliente_pedido_confirmado PRIMARY KEY (pedido_id),
    CONSTRAINT fk_cliente_pedido_confirmado_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    CONSTRAINT ck_cliente_pedido_confirmado_importe CHECK (importe_total > 0)
);

CREATE INDEX IF NOT EXISTS idx_cliente_pedido_confirmado_cliente ON cliente_pedido_confirmado (cliente_id);

COMMENT ON TABLE cliente_pedido_confirmado IS 'Proyeccion (dueno: servicio de clientes) alimentada por el evento PedidoConfirmado.';
COMMENT ON COLUMN cliente_pedido_confirmado.pedido_id IS 'Clave de idempotencia: el mismo evento recibido dos veces no duplica la fila.';

-- ----------------------------------------------------------------------------
-- Servicio de PEDIDOS
-- ----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS pedido_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pedido_linea_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pedido_confirmacion_intento_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pedido (
    id                        BIGINT                   NOT NULL,
    cliente_id                BIGINT                   NOT NULL,
    cliente_nombre            VARCHAR(120)             NOT NULL,
    cliente_email             VARCHAR(254)             NOT NULL,
    estado                    VARCHAR(32)              NOT NULL,
    fecha_creacion            TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_confirmacion        TIMESTAMP WITH TIME ZONE NULL,
    fecha_inicio_confirmacion TIMESTAMP WITH TIME ZONE NULL,
    importe_productos         NUMERIC(19, 2)           NOT NULL,
    coste_envio               NUMERIC(19, 2)           NULL,
    importe_total             NUMERIC(19, 2)           NULL,
    peso_total_kg             NUMERIC(10, 3)           NOT NULL,
    num_bultos                INTEGER                  NOT NULL,
    codigo_postal_destino     VARCHAR(10)              NOT NULL,
    plazo_envio_dias          INTEGER                  NULL,
    moneda                    CHAR(3)                  NOT NULL DEFAULT 'EUR',
    version                   BIGINT                   NOT NULL DEFAULT 0,
    num_intentos_confirmacion INTEGER                  NOT NULL DEFAULT 0,
    ultimo_error              VARCHAR(500)             NULL,
    CONSTRAINT pk_pedido PRIMARY KEY (id),
    CONSTRAINT ck_pedido_estado CHECK (estado IN ('PENDIENTE', 'CONFIRMANDO', 'CONFIRMADO', 'CANCELADO')),
    CONSTRAINT ck_pedido_importe_productos CHECK (importe_productos >= 0),
    CONSTRAINT ck_pedido_coste_envio CHECK (coste_envio IS NULL OR coste_envio >= 0),
    CONSTRAINT ck_pedido_importe_total CHECK (importe_total IS NULL OR importe_total >= 0),
    CONSTRAINT ck_pedido_bultos CHECK (num_bultos > 0),
    CONSTRAINT ck_pedido_peso CHECK (peso_total_kg >= 0),
    CONSTRAINT ck_pedido_plazo CHECK (plazo_envio_dias IS NULL OR plazo_envio_dias >= 0),
    CONSTRAINT ck_pedido_version CHECK (version >= 0),
    CONSTRAINT ck_pedido_intentos CHECK (num_intentos_confirmacion >= 0),
    -- Un pedido CONFIRMADO tiene coste, total, plazo y fecha de confirmacion...
    CONSTRAINT ck_pedido_confirmado_completo CHECK (estado <> 'CONFIRMADO' OR (coste_envio IS NOT NULL
        AND importe_total IS NOT NULL AND plazo_envio_dias IS NOT NULL AND fecha_confirmacion IS NOT NULL)),
    -- ...y ningun otro estado tiene coste de envio ni total (nunca hay un total "a medias").
    CONSTRAINT ck_pedido_sin_total_si_no_confirmado CHECK (estado = 'CONFIRMADO'
        OR (coste_envio IS NULL AND importe_total IS NULL)),
    -- El total es exactamente productos + envio (NUMERIC: suma exacta).
    CONSTRAINT ck_pedido_total_cuadra CHECK (importe_total IS NULL OR importe_total = importe_productos + coste_envio),
    -- CONFIRMANDO es un "lease": sin fecha de inicio la tarea de recuperacion no lo veria.
    CONSTRAINT ck_pedido_confirmando_con_lease CHECK (estado <> 'CONFIRMANDO' OR fecha_inicio_confirmacion IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_pedido_cliente ON pedido (cliente_id);
CREATE INDEX IF NOT EXISTS idx_pedido_estado ON pedido (estado);
CREATE INDEX IF NOT EXISTS idx_pedido_confirmando ON pedido (estado, fecha_inicio_confirmacion);

COMMENT ON TABLE pedido IS 'Pedido (agregado raiz). Los importes los calcula el servidor.';
COMMENT ON COLUMN pedido.cliente_nombre IS 'Instantanea del cliente al crear el pedido.';
COMMENT ON COLUMN pedido.fecha_inicio_confirmacion IS 'Inicio del lease del estado CONFIRMANDO.';
COMMENT ON COLUMN pedido.version IS 'Bloqueo optimista: se incrementa en cada transicion.';

CREATE TABLE IF NOT EXISTS pedido_linea (
    id               BIGINT         NOT NULL,
    pedido_id        BIGINT         NOT NULL,
    producto         VARCHAR(200)   NOT NULL,
    cantidad         INTEGER        NOT NULL,
    precio_unitario  NUMERIC(19, 4) NOT NULL,
    peso_unitario_kg NUMERIC(10, 3) NOT NULL,
    importe_linea    NUMERIC(19, 2) NOT NULL,
    CONSTRAINT pk_pedido_linea PRIMARY KEY (id),
    CONSTRAINT fk_pedido_linea_pedido FOREIGN KEY (pedido_id) REFERENCES pedido (id) ON DELETE CASCADE,
    CONSTRAINT ck_pedido_linea_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_pedido_linea_precio CHECK (precio_unitario >= 0),
    CONSTRAINT ck_pedido_linea_peso CHECK (peso_unitario_kg >= 0),
    -- Importe de la linea = cantidad x precio redondeado a centimos (HALF_UP).
    CONSTRAINT ck_pedido_linea_importe CHECK (importe_linea = ROUND(cantidad * precio_unitario, 2))
);

CREATE INDEX IF NOT EXISTS idx_pedido_linea_pedido ON pedido_linea (pedido_id);

COMMENT ON TABLE pedido_linea IS 'Lineas del pedido: producto, cantidad, precio unitario y peso unitario.';

CREATE TABLE IF NOT EXISTS pedido_confirmacion_intento (
    id                 BIGINT                   NOT NULL,
    pedido_id          BIGINT                   NOT NULL,
    resultado          VARCHAR(16)              NOT NULL,
    codigo_error       VARCHAR(64)              NULL,
    mensaje            VARCHAR(500)             NULL,
    coste_envio        NUMERIC(19, 2)           NULL,
    intentos_proveedor INTEGER                  NOT NULL DEFAULT 1,
    duracion_ms        BIGINT                   NOT NULL,
    fecha              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_pedido_confirmacion_intento PRIMARY KEY (id),
    CONSTRAINT fk_intento_pedido FOREIGN KEY (pedido_id) REFERENCES pedido (id) ON DELETE CASCADE,
    CONSTRAINT ck_intento_resultado CHECK (resultado IN ('OK', 'ERROR', 'TIMEOUT')),
    -- 0 intentos es valido: circuito abierto o limite saturado (no se llego a llamar).
    CONSTRAINT ck_intento_intentos CHECK (intentos_proveedor >= 0),
    CONSTRAINT ck_intento_duracion CHECK (duracion_ms >= 0),
    -- Un intento correcto siempre trae coste; uno fallido nunca.
    CONSTRAINT ck_intento_coste CHECK ((resultado = 'OK') = (coste_envio IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_intento_pedido ON pedido_confirmacion_intento (pedido_id, fecha);

COMMENT ON TABLE pedido_confirmacion_intento IS 'Auditoria de cada intento de confirmacion contra el proveedor logistico.';
