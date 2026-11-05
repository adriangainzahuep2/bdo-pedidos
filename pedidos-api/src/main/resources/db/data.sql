-- Datos de ejemplo para desarrollo y demostracion.
-- En produccion este fichero no se carga (spring.sql.init.mode=never).

INSERT INTO pedido (
    id, cliente_id, cliente_nombre, cliente_email, estado, fecha_creacion,
    fecha_confirmacion, fecha_inicio_confirmacion, importe_productos, coste_envio,
    importe_total, peso_total_kg, num_bultos, codigo_postal_destino, plazo_envio_dias,
    moneda, version, num_intentos_confirmacion, ultimo_error
) VALUES (
    nextval('pedido_id_seq'), 1, 'Cliente de Demostracion', 'demo@example.com', 'PENDIENTE',
    CURRENT_TIMESTAMP, NULL, NULL, 111.48, NULL, NULL, 1.700, 3, '43840', NULL,
    'EUR', 0, 0, NULL
);

INSERT INTO pedido_linea (
    id, pedido_id, producto, cantidad, precio_unitario, peso_unitario_kg, importe_linea
) VALUES
    (nextval('pedido_linea_id_seq'), (SELECT MAX(id) FROM pedido WHERE cliente_id = 1),
     'Teclado mecanico', 2, 45.9900, 0.800, 91.98),
    (nextval('pedido_linea_id_seq'), (SELECT MAX(id) FROM pedido WHERE cliente_id = 1),
     'Raton inalambrico', 1, 19.5000, 0.100, 19.50);
