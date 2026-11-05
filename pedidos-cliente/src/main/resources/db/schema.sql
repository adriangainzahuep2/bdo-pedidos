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
