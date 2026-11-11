package com.bdo.pedidos.cliente.eventos;

import com.bdo.pedidos.cliente.persistencia.PedidosConfirmadosRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Consumidor del evento PedidoConfirmado (Spring Cloud Stream, modelo funcional).
 *
 * <p>El bean {@code pedidoConfirmado} es un {@link Consumer}: Spring Cloud Stream lo enlaza
 * con el binding {@code pedidoConfirmado-in-0}, que la configuracion conecta al destino
 * {@code pedidos.confirmados} con el grupo {@code pedidos-cliente}. El grupo crea una cola
 * duradera propia: si este servicio esta parado, los eventos esperan en ella; con varias
 * instancias, cada evento lo procesa solo una.
 *
 * <p>Si el procesamiento falla (base de datos caida, cliente desconocido), el binder
 * reintenta con espera y, agotados los intentos, manda el mensaje a la cola de mensajes
 * muertos ({@code pedidos.confirmados.pedidos-cliente.dlq}) para revisarlo, sin bloquear
 * los siguientes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.eventos.habilitados", havingValue = "true")
public class ConsumidorEventosConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventosConfig.class);

    /** Tiempo maximo para guardar un evento: el listener no puede quedarse colgado. */
    private static final Duration MAXIMO = Duration.ofSeconds(10);

    /**
     * El tipo de entrada se declara en una clase ({@code implements Consumer<PedidoConfirmadoEvento>})
     * y no en una lambda: Spring Cloud Function lo necesita para convertir el JSON del
     * mensaje; con una lambda veia {@code Object} y entregaba los bytes sin convertir.
     */
    @Bean
    public ConsumidorPedidoConfirmado pedidoConfirmado(PedidosConfirmadosRepository proyeccion,
                                                       Clock clock,
                                                       MeterRegistry metricas) {
        return new ConsumidorPedidoConfirmado(proyeccion, clock, metricas);
    }

    /** Guarda cada PedidoConfirmado en la proyeccion local, tolerando duplicados. */
    public static class ConsumidorPedidoConfirmado implements Consumer<PedidoConfirmadoEvento> {

        private final PedidosConfirmadosRepository proyeccion;
        private final Clock clock;
        private final Counter nuevos;
        private final Counter duplicados;

        ConsumidorPedidoConfirmado(PedidosConfirmadosRepository proyeccion, Clock clock, MeterRegistry metricas) {
            this.proyeccion = proyeccion;
            this.clock = clock;
            this.nuevos = contador(metricas, "nuevo");
            this.duplicados = contador(metricas, "duplicado");
        }

        /**
         * El listener de RabbitMQ corre en su propio hilo (no en el event loop de WebFlux):
         * bloquear hasta que la fila esta guardada es lo correcto, porque el mensaje solo se
         * confirma al broker (ack) cuando este metodo termina sin error.
         */
        @Override
        public void accept(PedidoConfirmadoEvento evento) {
            Boolean nuevo = proyeccion.registrar(evento, clock.instant()).block(MAXIMO);
            if (Boolean.TRUE.equals(nuevo)) {
                nuevos.increment();
                log.info("PedidoConfirmado {} registrado para el cliente {} ({} {})",
                        evento.pedidoId(), evento.clienteId(), evento.importeTotal(), evento.moneda());
            } else {
                duplicados.increment();
                log.info("PedidoConfirmado {} repetido: ya estaba registrado, se ignora", evento.pedidoId());
            }
        }
    }

    private static Counter contador(MeterRegistry metricas, String resultado) {
        return Counter.builder("clientes.eventos.recibidos")
                .description("Eventos PedidoConfirmado procesados por el servicio de clientes")
                .tag("evento", "PedidoConfirmado")
                .tag("resultado", resultado)
                .register(metricas);
    }
}
