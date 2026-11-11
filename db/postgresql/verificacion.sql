-- ============================================================================
-- Verificacion del esquema y de los datos de prueba sobre PostgreSQL real.
--
-- Se ejecuta con psql -v ON_ERROR_STOP=1 despues de esquema.sql y datos-prueba.sql
-- (ver scripts/probar-postgresql.sh). Cada comprobacion que falla lanza una
-- excepcion y detiene la ejecucion. Todo se ejecuta en una transaccion que se
-- deshace al final: los datos de prueba quedan intactos.
-- ============================================================================

\set QUIET on
BEGIN;

-- ----------------------------------------------------------------------------
-- Utilidad: comprueba que una sentencia es RECHAZADA con el codigo SQLSTATE esperado.
-- ----------------------------------------------------------------------------
CREATE FUNCTION pg_temp.debe_fallar(sentencia text, sqlstate_esperado text, descripcion text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE sentencia;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE <> sqlstate_esperado THEN
            RAISE EXCEPTION 'FALLO [%]: se esperaba SQLSTATE % y se obtuvo % (%)',
                descripcion, sqlstate_esperado, SQLSTATE, SQLERRM;
        END IF;
        RAISE NOTICE 'OK  rechazado: %', descripcion;
        RETURN;
    END;
    RAISE EXCEPTION 'FALLO [%]: la sentencia se acepto y debia rechazarse', descripcion;
END;
$$;

-- ----------------------------------------------------------------------------
-- 1. Volumen: 10 filas por tabla
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    tabla text;
    filas bigint;
BEGIN
    FOREACH tabla IN ARRAY ARRAY['cliente', 'pedido', 'pedido_linea', 'pedido_confirmacion_intento', 'cliente_pedido_confirmado'] LOOP
        EXECUTE format('SELECT count(*) FROM %I', tabla) INTO filas;
        IF filas <> 10 THEN
            RAISE EXCEPTION 'FALLO: % tiene % filas (se esperaban 10)', tabla, filas;
        END IF;
        RAISE NOTICE 'OK  % tiene 10 filas', tabla;
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- 2. Invariantes de importes, pesos y estados
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    malos bigint;
BEGIN
    -- importe_productos = suma de las lineas (ya redondeadas)
    SELECT count(*) INTO malos FROM pedido p
     WHERE p.importe_productos <> (SELECT sum(l.importe_linea) FROM pedido_linea l WHERE l.pedido_id = p.id);
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos con importe_productos distinto de la suma de lineas', malos; END IF;
    RAISE NOTICE 'OK  importe_productos = suma de importe_linea en todos los pedidos';

    -- peso_total_kg = suma de cantidad x peso unitario
    SELECT count(*) INTO malos FROM pedido p
     WHERE p.peso_total_kg <> (SELECT sum(l.cantidad * l.peso_unitario_kg) FROM pedido_linea l WHERE l.pedido_id = p.id);
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos con peso_total_kg incorrecto', malos; END IF;
    RAISE NOTICE 'OK  peso_total_kg = suma de cantidad x peso_unitario_kg';

    -- importe_total = productos + envio en los confirmados; nulo en el resto
    SELECT count(*) INTO malos FROM pedido
     WHERE (estado = 'CONFIRMADO' AND importe_total <> importe_productos + coste_envio)
        OR (estado <> 'CONFIRMADO' AND (importe_total IS NOT NULL OR coste_envio IS NOT NULL));
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos con total incoherente', malos; END IF;
    RAISE NOTICE 'OK  importe_total = productos + envio solo en pedidos CONFIRMADO';

    -- Redondeo HALF_UP por linea: 3 x 0,3350 = 1,005 -> 1,01 y 3 x 12,4950 = 37,485 -> 37,49
    IF (SELECT importe_linea FROM pedido_linea WHERE id = 5) <> 1.01
       OR (SELECT importe_linea FROM pedido_linea WHERE id = 9) <> 37.49 THEN
        RAISE EXCEPTION 'FALLO: el redondeo por linea no es HALF_UP';
    END IF;
    RAISE NOTICE 'OK  redondeo HALF_UP por linea (1,005 -> 1,01; 37,485 -> 37,49)';

    -- Precision exacta: NUMERIC no arrastra errores binarios
    IF (SELECT sum(importe_total) FROM pedido WHERE estado = 'CONFIRMADO') <> 1397.26 THEN
        RAISE EXCEPTION 'FALLO: la suma de totales confirmados no es exacta';
    END IF;
    RAISE NOTICE 'OK  suma exacta de los totales confirmados = 1397.26';

    -- Cada pedido confirmado tiene exactamente un intento OK con el mismo coste
    SELECT count(*) INTO malos FROM pedido p
     WHERE p.estado = 'CONFIRMADO'
       AND (SELECT count(*) FROM pedido_confirmacion_intento i
             WHERE i.pedido_id = p.id AND i.resultado = 'OK' AND i.coste_envio = p.coste_envio) <> 1;
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos confirmados sin su intento OK', malos; END IF;
    RAISE NOTICE 'OK  cada pedido CONFIRMADO tiene un unico intento OK con su coste';

    -- num_intentos_confirmacion = intentos fallidos registrados
    SELECT count(*) INTO malos FROM pedido p
     WHERE p.num_intentos_confirmacion <> (SELECT count(*) FROM pedido_confirmacion_intento i
                                            WHERE i.pedido_id = p.id AND i.resultado <> 'OK');
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos con num_intentos_confirmacion incoherente', malos; END IF;
    RAISE NOTICE 'OK  num_intentos_confirmacion = intentos fallidos auditados';

    -- La instantanea del cliente coincide con el cliente (aqui comparten base de datos)
    SELECT count(*) INTO malos FROM pedido p JOIN cliente c ON c.id = p.cliente_id
     WHERE p.cliente_nombre <> c.nombre OR p.cliente_email <> c.email;
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % instantaneas de cliente distintas', malos; END IF;
    RAISE NOTICE 'OK  la instantanea nombre/email de cada pedido coincide con su cliente';

    -- Estan representados todos los estados
    IF (SELECT count(DISTINCT estado) FROM pedido) <> 4 THEN
        RAISE EXCEPTION 'FALLO: no estan representados los 4 estados';
    END IF;
    RAISE NOTICE 'OK  hay pedidos en los 4 estados (PENDIENTE, CONFIRMANDO, CONFIRMADO, CANCELADO)';
END $$;

-- ----------------------------------------------------------------------------
-- 2 bis. La proyeccion de clientes cuadra con los pedidos (eventual consistency)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    malos bigint;
BEGIN
    -- Cada pedido CONFIRMADO de la muestra esta en la proyeccion, con su cliente y su total.
    SELECT count(*) INTO malos FROM pedido p
      LEFT JOIN cliente_pedido_confirmado c ON c.pedido_id = p.id
     WHERE p.estado = 'CONFIRMADO'
       AND (c.pedido_id IS NULL OR c.cliente_id <> p.cliente_id OR c.importe_total <> p.importe_total
            OR c.moneda <> p.moneda OR c.fecha_confirmacion <> p.fecha_confirmacion);
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos confirmados sin reflejo exacto en la proyeccion', malos; END IF;
    RAISE NOTICE 'OK  cada pedido CONFIRMADO esta en la proyeccion de clientes con el mismo cliente, total y fecha';

    -- Ningun pedido que siga en la muestra sin confirmar aparece como confirmado.
    SELECT count(*) INTO malos FROM cliente_pedido_confirmado c JOIN pedido p ON p.id = c.pedido_id
     WHERE p.estado <> 'CONFIRMADO';
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % pedidos no confirmados en la proyeccion', malos; END IF;
    RAISE NOTICE 'OK  la proyeccion no contiene pedidos que no esten CONFIRMADOS';

    -- El evento se recibe despues de la confirmacion.
    SELECT count(*) INTO malos FROM cliente_pedido_confirmado WHERE fecha_recepcion < fecha_confirmacion;
    IF malos > 0 THEN RAISE EXCEPTION 'FALLO: % eventos recibidos antes de la confirmacion', malos; END IF;
    RAISE NOTICE 'OK  todos los eventos se recibieron despues de su confirmacion';
END $$;

-- ----------------------------------------------------------------------------
-- 3. Las restricciones rechazan los datos incoherentes
-- ----------------------------------------------------------------------------
\o /dev/null
SELECT pg_temp.debe_fallar(
    $$INSERT INTO cliente VALUES (11, 'Duplicado', 'ana.lopez@example.com', now())$$,
    '23505', 'email de cliente duplicado');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO cliente VALUES (11, NULL, 'nuevo@example.com', now())$$,
    '23502', 'cliente sin nombre');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO cliente_pedido_confirmado VALUES (2, 2, 208.95, 'EUR', now(), now())$$,
    '23505', 'evento PedidoConfirmado repetido (idempotencia por pedido)');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO cliente_pedido_confirmado VALUES (2001, 99, 10.00, 'EUR', now(), now())$$,
    '23503', 'evento de un cliente que no existe');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO cliente_pedido_confirmado VALUES (2002, 1, 0.00, 'EUR', now(), now())$$,
    '23514', 'pedido confirmado con total cero');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET estado = 'ENVIADO' WHERE id = 1$$,
    '23514', 'estado inexistente');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET estado = 'CONFIRMADO', fecha_confirmacion = now() WHERE id = 1$$,
    '23514', 'pedido CONFIRMADO sin coste de envio ni total');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET coste_envio = 5.00, importe_total = 96.98 WHERE id = 1$$,
    '23514', 'pedido PENDIENTE con coste y total');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET importe_total = 999.99 WHERE id = 2$$,
    '23514', 'total que no cuadra con productos + envio');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET fecha_inicio_confirmacion = NULL WHERE id = 3$$,
    '23514', 'pedido CONFIRMANDO sin fecha de inicio (lease)');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET num_bultos = 0 WHERE id = 1$$,
    '23514', 'pedido con cero bultos');
SELECT pg_temp.debe_fallar(
    $$UPDATE pedido SET importe_productos = -1 WHERE id = 1$$,
    '23514', 'importe negativo');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO pedido_linea VALUES (11, 1, 'Cable', 3, 0.3350, 0.050, 1.00)$$,
    '23514', 'linea con importe mal redondeado (1,00 en lugar de 1,01)');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO pedido_linea VALUES (11, 1, 'Cable', 0, 1.0000, 0.050, 0.00)$$,
    '23514', 'linea con cantidad cero');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO pedido_linea VALUES (11, 999, 'Cable', 1, 1.0000, 0.050, 1.00)$$,
    '23503', 'linea de un pedido inexistente');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO pedido_confirmacion_intento VALUES (11, 1, 'OK', NULL, 'ok', NULL, 1, 10, now())$$,
    '23514', 'intento OK sin coste de envio');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO pedido_confirmacion_intento VALUES (11, 1, 'ERROR', 'HTTP_503', 'x', NULL, -1, 10, now())$$,
    '23514', 'intento con numero de llamadas negativo');
SELECT pg_temp.debe_fallar(
    $$INSERT INTO pedido_confirmacion_intento VALUES (11, 1, 'REINTENTO', NULL, 'x', NULL, 1, 10, now())$$,
    '23514', 'intento con resultado inexistente');
\o

-- ----------------------------------------------------------------------------
-- 4. Concurrencia: el compare-and-set de la aplicacion
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    filas bigint;
BEGIN
    -- Primera confirmacion: toma el pedido 1 (PENDIENTE, version 0).
    UPDATE pedido SET estado = 'CONFIRMANDO', fecha_inicio_confirmacion = now(), version = version + 1
     WHERE id = 1 AND estado = 'PENDIENTE' AND version = 0;
    GET DIAGNOSTICS filas = ROW_COUNT;
    IF filas <> 1 THEN RAISE EXCEPTION 'FALLO: la primera confirmacion no ha tomado el pedido'; END IF;

    -- Segunda confirmacion con la misma lectura: no actualiza nada (recibiria 409).
    UPDATE pedido SET estado = 'CONFIRMANDO', fecha_inicio_confirmacion = now(), version = version + 1
     WHERE id = 1 AND estado = 'PENDIENTE' AND version = 0;
    GET DIAGNOSTICS filas = ROW_COUNT;
    IF filas <> 0 THEN RAISE EXCEPTION 'FALLO: la segunda confirmacion ha tomado tambien el pedido'; END IF;
    RAISE NOTICE 'OK  compare-and-set: solo una de dos confirmaciones toma el pedido';

    -- Fase 3: se confirma con la version leida tras tomarlo.
    UPDATE pedido SET estado = 'CONFIRMADO', coste_envio = 7.95, importe_total = importe_productos + 7.95,
                      plazo_envio_dias = 2, fecha_confirmacion = now(), fecha_inicio_confirmacion = NULL,
                      version = version + 1
     WHERE id = 1 AND estado = 'CONFIRMANDO' AND version = 1;
    GET DIAGNOSTICS filas = ROW_COUNT;
    IF filas <> 1 OR (SELECT importe_total FROM pedido WHERE id = 1) <> 99.93 THEN
        RAISE EXCEPTION 'FALLO: la confirmacion del pedido 1 no ha quedado coherente';
    END IF;
    RAISE NOTICE 'OK  confirmacion en tres fases: total 91,98 + 7,95 = 99,93';
END $$;

-- ----------------------------------------------------------------------------
-- 5. Recuperacion de confirmaciones abandonadas (misma sentencia que la aplicacion)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    filas bigint;
BEGIN
    UPDATE pedido SET estado = 'PENDIENTE', ultimo_error = 'Confirmacion abandonada',
                      fecha_inicio_confirmacion = NULL,
                      num_intentos_confirmacion = num_intentos_confirmacion + 1, version = version + 1
     WHERE estado = 'CONFIRMANDO' AND fecha_inicio_confirmacion < TIMESTAMPTZ '2026-11-01 10:13:00+00';
    GET DIAGNOSTICS filas = ROW_COUNT;
    IF filas <> 1 OR (SELECT estado FROM pedido WHERE id = 3) <> 'PENDIENTE' THEN
        RAISE EXCEPTION 'FALLO: la recuperacion no ha devuelto el pedido 3 a PENDIENTE';
    END IF;
    RAISE NOTICE 'OK  la recuperacion devuelve a PENDIENTE el pedido con el lease caducado';
END $$;

-- ----------------------------------------------------------------------------
-- 6. Borrado en cascada de lineas e intentos
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    DELETE FROM pedido WHERE id = 9;
    IF EXISTS (SELECT 1 FROM pedido_linea WHERE pedido_id = 9)
       OR EXISTS (SELECT 1 FROM pedido_confirmacion_intento WHERE pedido_id = 9) THEN
        RAISE EXCEPTION 'FALLO: el borrado del pedido no arrastra sus lineas e intentos';
    END IF;
    RAISE NOTICE 'OK  borrar un pedido borra sus lineas y sus intentos (ON DELETE CASCADE)';
END $$;

ROLLBACK;

\echo '========== VERIFICACION DE POSTGRESQL CORRECTA =========='
