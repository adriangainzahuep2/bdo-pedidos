package com.bdo.pedidos.cliente;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Microservicio de clientes (bounded context propio).
 *
 * <p>Publica {@code /api/clientes} y es el dueno de la tabla {@code cliente}. El servicio de
 * pedidos lo consume por HTTP: cada microservicio tiene su base de datos y su ciclo de vida.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDiscoveryClient
public class ClienteApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClienteApiApplication.class, args);
    }
}
