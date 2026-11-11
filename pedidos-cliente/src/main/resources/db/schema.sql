-- ============================================================================
-- Esquema del microservicio de CLIENTES (H2 en modo PostgreSQL / PostgreSQL)
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS cliente_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS cliente (
    id             BIGINT        NOT NULL,
    nombre         VARCHAR(120) NOT NULL,
    email          VARCHAR(254) NOT NULL,
    fecha_creacion TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_cliente PRIMARY KEY (id),
    CONSTRAINT uk_cliente_email UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_cliente_email ON cliente (email);

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
