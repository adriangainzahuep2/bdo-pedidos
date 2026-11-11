-- ============================================================================
-- Datos de prueba: 10 filas por tabla, coherentes con las reglas del dominio.
--
-- Cubren todos los estados y escenarios del enunciado:
--   pedido 1  PENDIENTE   recien creado
--   pedido 2  CONFIRMADO  a la primera
--   pedido 3  CONFIRMANDO confirmacion en curso (lease abierto)
--   pedido 4  CANCELADO   tras un intento fallido (502 del proveedor)
--   pedido 5  PENDIENTE   tras dos 503 seguidos (reintentable); linea con redondeo HALF_UP
--   pedido 6  CONFIRMADO  pedido de importe alto
--   pedido 7  PENDIENTE   tras un timeout del proveedor
--   pedido 8  PENDIENTE   tras circuito abierto (0 llamadas al proveedor)
--   pedido 9  CONFIRMADO  despues de un 503 (el segundo intento fue bien); redondeo HALF_UP
--   pedido 10 CONFIRMADO  productos sin peso (suscripcion)
--
-- Reglas que respetan (y que comprueba verificacion.sql):
--   importe_linea     = ROUND(cantidad x precio_unitario, 2)
--   importe_productos = suma de importe_linea del pedido
--   peso_total_kg     = suma de cantidad x peso_unitario_kg
--   importe_total     = importe_productos + coste_envio (solo CONFIRMADO)
--   num_intentos_confirmacion = intentos fallidos registrados del pedido
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- cliente (10)
-- ----------------------------------------------------------------------------
INSERT INTO cliente (id, nombre, email, fecha_creacion) VALUES
    (1,  'Ana Lopez Garcia',          'ana.lopez@example.com',         '2026-10-01 09:00:00+00'),
    (2,  'Distribuciones Ebro S.L.',  'compras@ebro.example.com',      '2026-10-01 09:15:00+00'),
    (3,  'Carlos Martinez Ruiz',      'carlos.martinez@example.com',   '2026-10-02 10:30:00+00'),
    (4,  'Lucia Fernandez Soto',      'lucia.fernandez@example.com',   '2026-10-03 11:45:00+00'),
    (5,  'Ferreteria Delta S.A.',     'pedidos@ferreteria-delta.example.com', '2026-10-04 08:20:00+00'),
    (6,  'Javier Sanchez Moreno',     'javier.sanchez@example.com',    '2026-10-05 16:10:00+00'),
    (7,  'Oficinas Tarraco S.L.',     'admin@tarraco.example.com',     '2026-10-06 12:00:00+00'),
    (8,  'Marta Gomez Vidal',         'marta.gomez@example.com',       '2026-10-07 18:40:00+00'),
    (9,  'Iluminacion Norte S.L.',    'ventas@iluminacion-norte.example.com', '2026-10-08 07:55:00+00'),
    (10, 'Pablo Navarro Gil',         'pablo.navarro@example.com',     '2026-10-09 13:25:00+00');

SELECT setval('cliente_id_seq', 10);

-- ----------------------------------------------------------------------------
-- cliente_pedido_confirmado (10): proyeccion del servicio de clientes
--   Los pedidos 2, 6, 9 y 10 son los CONFIRMADOS de la tabla pedido (cuadran con
--   ella); 1001-1006 son confirmaciones anteriores a esta muestra de pedidos.
-- ----------------------------------------------------------------------------
INSERT INTO cliente_pedido_confirmado
    (pedido_id, cliente_id, importe_total, moneda, fecha_confirmacion, fecha_recepcion) VALUES
    (2,    2,  208.95,  'EUR', '2026-11-01 10:06:02+00', '2026-11-01 10:06:02.150+00'),
    (6,    6,  1111.40, 'EUR', '2026-11-01 10:26:01+00', '2026-11-01 10:26:01.090+00'),
    (9,    9,  42.99,   'EUR', '2026-11-01 10:45:03+00', '2026-11-01 10:45:03.210+00'),
    (10,   10, 33.92,   'EUR', '2026-11-01 10:51:00+00', '2026-11-01 10:51:00.080+00'),
    (1001, 1,  56.40,   'EUR', '2026-10-12 09:30:00+00', '2026-10-12 09:30:00.120+00'),
    (1002, 1,  18.95,   'EUR', '2026-10-20 17:05:00+00', '2026-10-20 17:05:00.200+00'),
    (1003, 2,  1520.00, 'EUR', '2026-10-15 11:00:00+00', '2026-10-15 11:00:00.100+00'),
    (1004, 3,  74.30,   'EUR', '2026-10-18 13:45:00+00', '2026-10-18 13:45:00.090+00'),
    (1005, 7,  310.75,  'EUR', '2026-10-22 08:15:00+00', '2026-10-22 08:15:00.300+00'),
    (1006, 9,  27.80,   'EUR', '2026-10-25 19:20:00+00', '2026-10-25 19:20:00.110+00');

-- ----------------------------------------------------------------------------
-- pedido (10)
-- ----------------------------------------------------------------------------
INSERT INTO pedido (
    id, cliente_id, cliente_nombre, cliente_email, estado, fecha_creacion,
    fecha_confirmacion, fecha_inicio_confirmacion, importe_productos, coste_envio,
    importe_total, peso_total_kg, num_bultos, codigo_postal_destino, plazo_envio_dias,
    moneda, version, num_intentos_confirmacion, ultimo_error
) VALUES
    (1, 1, 'Ana Lopez Garcia', 'ana.lopez@example.com', 'PENDIENTE', '2026-11-01 10:00:00+00',
     NULL, NULL, 91.98, NULL, NULL, 1.600, 2, '43840', NULL, 'EUR', 0, 0, NULL),
    (2, 2, 'Distribuciones Ebro S.L.', 'compras@ebro.example.com', 'CONFIRMADO', '2026-11-01 10:05:00+00',
     '2026-11-01 10:06:02+00', NULL, 199.00, 9.95, 208.95, 4.500, 1, '50001', 2, 'EUR', 2, 0, NULL),
    (3, 3, 'Carlos Martinez Ruiz', 'carlos.martinez@example.com', 'CONFIRMANDO', '2026-11-01 10:10:00+00',
     NULL, '2026-11-01 10:11:00+00', 58.50, NULL, NULL, 0.300, 3, '28013', NULL, 'EUR', 1, 0, NULL),
    (4, 4, 'Lucia Fernandez Soto', 'lucia.fernandez@example.com', 'CANCELADO', '2026-11-01 10:15:00+00',
     NULL, NULL, 129.99, NULL, NULL, 12.000, 1, '08001', NULL, 'EUR', 3, 1,
     'El proveedor logistico respondio con HTTP 502'),
    (5, 5, 'Ferreteria Delta S.A.', 'pedidos@ferreteria-delta.example.com', 'PENDIENTE', '2026-11-01 10:20:00+00',
     NULL, NULL, 1.01, NULL, NULL, 0.150, 3, '41001', NULL, 'EUR', 4, 2,
     'El proveedor logistico respondio con HTTP 503'),
    (6, 6, 'Javier Sanchez Moreno', 'javier.sanchez@example.com', 'CONFIRMADO', '2026-11-01 10:25:00+00',
     '2026-11-01 10:26:01+00', NULL, 1099.00, 12.40, 1111.40, 2.200, 1, '46001', 1, 'EUR', 2, 0, NULL),
    (7, 7, 'Oficinas Tarraco S.L.', 'admin@tarraco.example.com', 'PENDIENTE', '2026-11-01 10:30:00+00',
     NULL, NULL, 249.50, NULL, NULL, 7.800, 1, '43001', NULL, 'EUR', 2, 1,
     'El proveedor logistico no ha respondido en 6000 ms'),
    (8, 8, 'Marta Gomez Vidal', 'marta.gomez@example.com', 'PENDIENTE', '2026-11-01 10:35:00+00',
     NULL, NULL, 119.98, NULL, NULL, 0.700, 2, '15001', NULL, 'EUR', 2, 1,
     'El circuito hacia el proveedor logistico esta abierto: no se ha realizado ninguna llamada'),
    (9, 9, 'Iluminacion Norte S.L.', 'ventas@iluminacion-norte.example.com', 'CONFIRMADO', '2026-11-01 10:40:00+00',
     '2026-11-01 10:45:03+00', NULL, 37.49, 5.50, 42.99, 2.250, 3, '33001', 3, 'EUR', 4, 1, NULL),
    (10, 10, 'Pablo Navarro Gil', 'pablo.navarro@example.com', 'CONFIRMADO', '2026-11-01 10:50:00+00',
     '2026-11-01 10:51:00+00', NULL, 29.97, 3.95, 33.92, 0.000, 3, '29001', 2, 'EUR', 2, 0, NULL);

SELECT setval('pedido_id_seq', 10);

-- ----------------------------------------------------------------------------
-- pedido_linea (10): una linea por pedido
-- ----------------------------------------------------------------------------
INSERT INTO pedido_linea (id, pedido_id, producto, cantidad, precio_unitario, peso_unitario_kg, importe_linea) VALUES
    (1,  1,  'Teclado mecanico',      2, 45.9900,    0.800,  91.98),
    (2,  2,  'Monitor 27 pulgadas',   1, 199.0000,   4.500,  199.00),
    (3,  3,  'Raton inalambrico',     3, 19.5000,    0.100,  58.50),
    (4,  4,  'Silla ergonomica',      1, 129.9900,   12.000, 129.99),
    -- 3 x 0,3350 = 1,005 -> 1,01 (HALF_UP)
    (5,  5,  'Cable USB-C',           3, 0.3350,     0.050,  1.01),
    (6,  6,  'Portatil 14 pulgadas',  1, 1099.0000,  2.200,  1099.00),
    (7,  7,  'Impresora laser',       1, 249.5000,   7.800,  249.50),
    (8,  8,  'Auriculares',           2, 59.9900,    0.350,  119.98),
    -- 3 x 12,4950 = 37,485 -> 37,49 (HALF_UP)
    (9,  9,  'Lampara LED',           3, 12.4950,    0.750,  37.49),
    (10, 10, 'Suscripcion mensual',   3, 9.9900,     0.000,  29.97);

SELECT setval('pedido_linea_id_seq', 10);

-- ----------------------------------------------------------------------------
-- pedido_confirmacion_intento (10)
-- ----------------------------------------------------------------------------
INSERT INTO pedido_confirmacion_intento (
    id, pedido_id, resultado, codigo_error, mensaje, coste_envio, intentos_proveedor, duracion_ms, fecha
) VALUES
    (1,  2, 'OK',      NULL,               'Confirmacion correcta', 9.95,  1, 412,  '2026-11-01 10:06:02+00'),
    (2,  4, 'ERROR',   'HTTP_502',         'El proveedor logistico respondio con HTTP 502', NULL, 1, 95, '2026-11-01 10:16:00+00'),
    (3,  5, 'ERROR',   'HTTP_503',         'El proveedor logistico respondio con HTTP 503', NULL, 3, 780, '2026-11-01 10:21:00+00'),
    (4,  5, 'ERROR',   'HTTP_503',         'El proveedor logistico respondio con HTTP 503', NULL, 3, 812, '2026-11-01 10:23:00+00'),
    (5,  6, 'OK',      NULL,               'Confirmacion correcta', 12.40, 1, 4870, '2026-11-01 10:26:01+00'),
    (6,  7, 'TIMEOUT', 'TIMEOUT',          'El proveedor logistico no ha respondido en 6000 ms', NULL, 1, 6003, '2026-11-01 10:31:06+00'),
    (7,  8, 'ERROR',   'CIRCUITO_ABIERTO', 'El circuito hacia el proveedor logistico esta abierto: no se ha realizado ninguna llamada', NULL, 0, 1, '2026-11-01 10:36:00+00'),
    (8,  9, 'ERROR',   'HTTP_503',         'El proveedor logistico respondio con HTTP 503', NULL, 3, 640, '2026-11-01 10:41:00+00'),
    (9,  9, 'OK',      NULL,               'Confirmacion correcta', 5.50,  2, 655,  '2026-11-01 10:45:03+00'),
    (10, 10, 'OK',     NULL,               'Confirmacion correcta', 3.95,  1, 233,  '2026-11-01 10:51:00+00');

SELECT setval('pedido_confirmacion_intento_id_seq', 10);

COMMIT;
