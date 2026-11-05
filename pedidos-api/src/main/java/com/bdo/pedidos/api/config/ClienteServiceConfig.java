package com.bdo.pedidos.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Cliente HTTP del servicio de clientes (microservicio hermano).
 *
 * <p>Con {@code app.clientes.load-balanced=true} (valor por defecto) la URL es un
 * <i>service id</i> que resuelve Spring Cloud LoadBalancer usando el registro de Eureka:
 * es el equivalente actual de Ribbon en el ecosistema Netflix OSS, y es lo que permite
 * tener varias instancias del servicio de clientes detras de una sola direccion.
 *
 * <p>Con {@code app.clientes.load-balanced=false} se usa la URL tal cual (util en tests,
 * donde se apunta a un stub local).
 *
 * <p>Las dos implementaciones dan nombre al bean igual ({@code webClientClientes}) para que
 * el punto de inyeccion no dependa del modo de despliegue.
 */
@Configuration(proxyBeanMethods = false)
public class ClienteServiceConfig {

    /** Configuracion con descubrimiento de servicios y balanceo de carga. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.clientes.load-balanced", havingValue = "true", matchIfMissing = true)
    static class ConBalanceoDeCarga {

        /**
         * Constructor de WebClient con balanceo de carga.
         *
         * <p>La anotacion {@link LoadBalanced} es la que Spring Cloud LoadBalancer detecta
         * (mediante su BeanPostProcessor) para anadir el filtro que traduce el
         * <i>service id</i> a una direccion concreta de una instancia registrada.
         */
        @Bean
        @LoadBalanced
        WebClient.Builder loadBalancedWebClientBuilder() {
            return WebClient.builder();
        }

        @Bean
        WebClient webClientClientes(ClientesProperties properties, @LoadBalanced WebClient.Builder builder) {
            return builder.baseUrl(properties.getUrl()).build();
        }
    }

    /** Configuracion directa, sin balanceo de carga (entornos locales y tests). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.clientes.load-balanced", havingValue = "false")
    static class Directo {

        @Bean
        WebClient webClientClientes(ClientesProperties properties) {
            return WebClient.builder().baseUrl(properties.getUrl()).build();
        }
    }
}
