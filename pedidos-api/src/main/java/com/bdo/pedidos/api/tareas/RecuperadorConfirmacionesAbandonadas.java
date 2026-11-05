package com.bdo.pedidos.api.tareas;

import com.bdo.pedidos.dominio.puerto.PedidoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Recuperacion de pedidos que se quedaron en estado {@code CONFIRMANDO}.
 *
 * <p>Escenario: el proceso muere (despliegue, OOM, nodo caido) entre tomar el pedido y
 * persistir la tarifa. El pedido se queda en CONFIRMANDO y, sin este trabajo, nadie podria
 * volver a confirmarlo. Cada minuto se devuelven a PENDIENTE los que llevan mas del "lease"
 * configurado sin actualizarse.
 *
 * <p>El lease (2 minutos por defecto) es holgadamente superior al peor caso del flujo
 * (8 s de presupuesto global + persistencia), de modo que nunca se roba un pedido que esta
 * siendo confirmado ahora mismo.
 */
@Component
public class RecuperadorConfirmacionesAbandonadas {

    private static final Logger log = LoggerFactory.getLogger(RecuperadorConfirmacionesAbandonadas.class);

    private final PedidoRepositoryPort pedidos;
    private final Clock clock;
    private final Duration lease;

    public RecuperadorConfirmacionesAbandonadas(PedidoRepositoryPort pedidos,
                                                 Clock clock,
                                                 @Value("${app.recuperacion.confirmaciones.lease-ms:120000}") long leaseMs) {
        this.pedidos = pedidos;
        this.clock = clock;
        this.lease = Duration.ofMillis(leaseMs);
    }

    @Scheduled(fixedDelayString = "${app.recuperacion.confirmaciones.intervalo-ms:60000}")
    public void recuperar() {
        Instant limite = clock.instant().minus(lease);
        try {
            pedidos.recuperarConfirmacionesAbandonadas(limite)
                    .doOnNext(recuperados -> {
                        if (recuperados != null && recuperados > 0) {
                            log.warn("Se han devuelto a PENDIENTE {} pedidos con la confirmacion abandonada", recuperados);
                        }
                    })
                    .onErrorResume(error -> {
                        log.error("No se pudo ejecutar la recuperacion de confirmaciones abandonadas", error);
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block(Duration.ofSeconds(10));
        } catch (RuntimeException error) {
            log.error("Fallo en la recuperacion de confirmaciones abandonadas", error);
        }
    }
}
