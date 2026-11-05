package com.bdo.pedidos.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Servidor de descubrimiento de servicios (Eureka).
 *
 * <p>Los microservicios se registran aqui y el gateway (y el servicio de pedidos) resuelven
 * las direcciones de los demas a traves de Spring Cloud LoadBalancer.
 */
@SpringBootApplication
@EnableEurekaServer
public class RegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistryApplication.class, args);
    }
}
