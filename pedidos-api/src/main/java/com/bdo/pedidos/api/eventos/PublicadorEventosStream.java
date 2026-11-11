package com.bdo.pedidos.api.eventos;

import com.bdo.pedidos.dominio.evento.PedidoConfirmado;
import com.bdo.pedidos.dominio.puerto.EventosPedidoPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Adaptador de {@link EventosPedidoPort} sobre Spring Cloud Stream.
 *
 * <p>{@link StreamBridge} envia el evento al binding {@value #BINDING}, que la configuracion
 * enlaza con el destino {@code pedidos.confirmados} del broker (un exchange de RabbitMQ). El
 * codigo no conoce el broker: cambiar RabbitMQ por Kafka es cambiar el binder, no esta clase.
 *
 * <p>El envio del binder de RabbitMQ es bloqueante (espera al socket), asi que se hace en
 * {@code boundedElastic}, nunca en el event loop. Cualquier fallo se registra en el log y en
 * la metrica {@code pedidos.eventos.publicados{resultado="error"}} y no se propaga: ver el
 * contrato del puerto.
 */
public class PublicadorEventosStream implements EventosPedidoPort {

    /** Binding de salida (nombre funcional: {@code <nombre>-out-<indice>}). */
    public static final String BINDING = "pedidoConfirmado-out-0";

    private static final Logger log = LoggerFactory.getLogger(PublicadorEventosStream.class);

    private final StreamBridge streamBridge;
    private final Counter publicados;
    private final Counter fallidos;

    public PublicadorEventosStream(StreamBridge streamBridge, MeterRegistry metricas) {
        this.streamBridge = streamBridge;
        this.publicados = contador(metricas, "ok");
        this.fallidos = contador(metricas, "error");
    }

    @Override
    public Mono<Void> publicar(PedidoConfirmado evento) {
        return Mono.fromCallable(() -> streamBridge.send(BINDING, mensaje(evento)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(enviado -> {
                    if (Boolean.TRUE.equals(enviado)) {
                        publicados.increment();
                        log.info("Evento PedidoConfirmado publicado (pedido {})", evento.pedidoId());
                    } else {
                        fallidos.increment();
                        log.warn("El broker no acepto el evento PedidoConfirmado del pedido {}", evento.pedidoId());
                    }
                })
                .onErrorResume(error -> {
                    fallidos.increment();
                    log.warn("No se pudo publicar PedidoConfirmado del pedido {}: {}",
                            evento.pedidoId(), error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private static Message<PedidoConfirmado> mensaje(PedidoConfirmado evento) {
        return MessageBuilder.withPayload(evento)
                // Cabeceras de negocio: permiten filtrar o enrutar sin deserializar el cuerpo.
                .setHeader("tipoEvento", "PedidoConfirmado")
                .setHeader("pedidoId", evento.pedidoId())
                .build();
    }

    private static Counter contador(MeterRegistry metricas, String resultado) {
        return Counter.builder("pedidos.eventos.publicados")
                .description("Eventos de dominio publicados en el broker")
                .tag("evento", "PedidoConfirmado")
                .tag("resultado", resultado)
                .register(metricas);
    }
}
