package com.bdo.pedidos.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Servicio de pedidos.
 *
 * <p>Arquitectura: microservicio reactivo (Spring Boot 3 / WebFlux) con Spring Data R2DBC,
 * Resource Server OAuth2 (JWT), descubrimiento de servicios (Eureka) y balanceo de carga
 * (Spring Cloud LoadBalancer). El dominio vive en el modulo {@code pedidos-dominio} y no
 * depende de nada de Spring.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDiscoveryClient
@EnableScheduling
public class PedidosApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PedidosApiApplication.class, args);
    }
}
