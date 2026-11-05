package com.bdo.pedidos.api.proveedor;

import com.bdo.pedidos.api.config.ProveedorLogisticoProperties;
import com.bdo.pedidos.dominio.error.ProveedorHttpException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Espera entre reintentos, comun a los dos adaptadores del proveedor.
 *
 * <ul>
 *   <li>Si el proveedor envia {@code Retry-After}, se respeta.</li>
 *   <li>Si no, backoff exponencial ({@code backoff-inicial x multiplicador^(n-1)}) con
 *       jitter (+/- {@code backoff-jitter}): evita que todas las instancias reintenten en
 *       el mismo instante (thundering herd).</li>
 * </ul>
 */
public final class PoliticaEsperaReintentos {

    private final ProveedorLogisticoProperties properties;

    public PoliticaEsperaReintentos(ProveedorLogisticoProperties properties) {
        this.properties = properties;
    }

    /**
     * @param causa    fallo del intento anterior
     * @param reintento numero de reintento (1 = primer reintento)
     */
    public Duration esperaAntesDe(Throwable causa, long reintento) {
        if (causa instanceof ProveedorHttpException http && http.retryAfterSegundos() != null) {
            return Duration.ofSeconds(http.retryAfterSegundos());
        }
        double factor = Math.pow(properties.getBackoffMultiplicador(), Math.max(0, reintento - 1));
        long base = (long) (properties.getBackoffInicial().toMillis() * factor);
        double jitter = properties.getBackoffJitter();
        double factorJitter = jitter > 0
                ? 1.0d + ThreadLocalRandom.current().nextDouble(-jitter, jitter)
                : 1.0d;
        return Duration.ofMillis(Math.max(1L, (long) (base * factorJitter)));
    }
}
