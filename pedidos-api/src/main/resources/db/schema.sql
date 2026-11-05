-- ============================================================================
-- Esquema del microservicio de PEDIDOS
--
-- Portable entre H2 (modo PostgreSQL) y PostgreSQL: se usa en los tests (H2 en
-- memoria) y en produccion (PostgreSQL) el mismo DDL.
--
-- Decisiones de modelado:
--   * Los importes son NUMERIC (BigDecimal): nunca DOUBLE PRECISION, porque el
--     redondeo binario introduce centimos de error (0.1 + 0.2 != 0.3).
--   * Los precios unitarios tienen 4 decimales (precision del catalogo) y los
--     importes 2 (centimos). El redondeo a centimos se aplica en la linea.
--   * Las fechas son TIMESTAMP WITH TIME ZONE y se guardan siempre en UTC.
--   * El identificador se genera con una secuencia de la base de datos
--     (nextval), no con IDENTITY: H2 no soporta "INSERT ... RETURNING", que es
--     justo lo que necesita el save() autogenerado de Spring Data R2DBC.
--   * La version se usa como bloqueo optimista: las transiciones de estado son
--     "UPDATE ... WHERE estado = ? AND version = ?" (compare-and-set).
--   * Las invariantes del pedido viven en el dominio y, ademas, en CHECK.
--   * Version consolidada (clientes + pedidos) para PostgreSQL: db/postgresql/esquema.sql.
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS pedido_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pedido_linea_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pedido_confirmacion_intento_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pedido (
    id                        BIGINT         NOT NULL,
    cliente_id                BIGINT         NOT NULL,
    -- Instantanea del cliente en el momento de la creacion: el pedido historico
    -- no debe cambiar si el cliente renombra su empresa o cambia de email.
    cliente_nombre            VARCHAR(120)   NOT NULL,
    cliente_email             VARCHAR(254)   NOT NULL,
    estado                    VARCHAR(32)    NOT NULL,
    fecha_creacion            TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_confirmacion        TIMESTAMP WITH TIME ZONE NULL,
    -- Momento en el que se tomo el pedido para confirmar (lease del estado CONFIRMANDO).
    fecha_inicio_confirmacion TIMESTAMP WITH TIME ZONE NULL,
    importe_productos         NUMERIC(19, 2) NOT NULL,
    coste_envio               NUMERIC(19, 2) NULL,
    importe_total             NUMERIC(19, 2) NULL,
    peso_total_kg             NUMERIC(10, 3) NOT NULL,
    num_bultos                INTEGER         NOT NULL,
    codigo_postal_destino     VARCHAR(10)    NOT NULL,
    plazo_envio_dias          INTEGER         NULL,
    moneda                    CHAR(3)        NOT NULL DEFAULT 'EUR',
    version                   BIGINT         NOT NULL DEFAULT 0,
    num_intentos_confirmacion INTEGER         NOT NULL DEFAULT 0,
    ultimo_error              VARCHAR(500)   NULL,
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
-- Indice de apoyo para la recuperacion de confirmaciones abandonadas.
-- (Indice compuesto y no parcial: H2 no admite "CREATE INDEX ... WHERE", y el DDL
--  debe ser identico en H2 y PostgreSQL.)
CREATE INDEX IF NOT EXISTS idx_pedido_confirmando ON pedido (estado, fecha_inicio_confirmacion);

CREATE TABLE IF NOT EXISTS pedido_linea (
    id              BIGINT         NOT NULL,
    pedido_id       BIGINT         NOT NULL,
    producto        VARCHAR(200)   NOT NULL,
    cantidad        INTEGER        NOT NULL,
    precio_unitario NUMERIC(19, 4) NOT NULL,
    peso_unitario_kg NUMERIC(10, 3) NOT NULL,
    importe_linea   NUMERIC(19, 2) NOT NULL,
    CONSTRAINT pk_pedido_linea PRIMARY KEY (id),
    CONSTRAINT fk_pedido_linea_pedido FOREIGN KEY (pedido_id) REFERENCES pedido (id) ON DELETE CASCADE,
    CONSTRAINT ck_pedido_linea_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_pedido_linea_precio CHECK (precio_unitario >= 0),
    CONSTRAINT ck_pedido_linea_peso CHECK (peso_unitario_kg >= 0),
    -- Importe de la linea = cantidad x precio redondeado a centimos (HALF_UP).
    CONSTRAINT ck_pedido_linea_importe CHECK (importe_linea = ROUND(cantidad * precio_unitario, 2))
);

CREATE INDEX IF NOT EXISTS idx_pedido_linea_pedido ON pedido_linea (pedido_id);

-- Traza de auditoria de cada intento de confirmacion: que se intento, con que
-- resultado, cuantos intentos se hicieron contra el proveedor y cuanto tardo.
CREATE TABLE IF NOT EXISTS pedido_confirmacion_intento (
    id                BIGINT         NOT NULL,
    pedido_id         BIGINT         NOT NULL,
    resultado         VARCHAR(16)    NOT NULL,
    codigo_error      VARCHAR(64)    NULL,
    mensaje           VARCHAR(500)   NULL,
    coste_envio       NUMERIC(19, 2) NULL,
    intentos_proveedor INTEGER        NOT NULL DEFAULT 1,
    duracion_ms       BIGINT         NOT NULL,
    fecha             TIMESTAMP WITH TIME ZONE NOT NULL,
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
