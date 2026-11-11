package com.bdo.pedidos.api.eventos;

import com.bdo.pedidos.dominio.puerto.EventosPedidoPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Eleccion del adaptador de eventos.
 *
 * <p>Con {@code app.eventos.habilitados=true} (perfil {@code eventos}, el del stack de Docker)
 * los eventos van al broker. Sin el, un adaptador que no hace nada: el E2E local y el
 * pipeline de CI no tienen RabbitMQ, y la confirmacion de pedidos no depende de el.
 */
@Configuration(proxyBeanMethods = false)
public class EventosConfig {

    private static final Logger log = LoggerFactory.getLogger(EventosConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.eventos.habilitados", havingValue = "true")
    public EventosPedidoPort publicadorEventosStream(StreamBridge streamBridge, MeterRegistry metricas) {
        log.info("Eventos de dominio: se publican en el broker (Spring Cloud Stream)");
        return new PublicadorEventosStream(streamBridge, metricas);
    }

    @Bean
    @ConditionalOnProperty(name = "app.eventos.habilitados", havingValue = "false", matchIfMissing = true)
    public EventosPedidoPort publicadorEventosDesactivado() {
        log.info("Eventos de dominio desactivados (app.eventos.habilitados=false)");
        return evento -> Mono.empty();
    }
}
