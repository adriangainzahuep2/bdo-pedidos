package com.bdo.pedidos.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API Gateway: punto de entrada unico del sistema.
 *
 * <p>Responsabilidades: validar el token (Resource Server OAuth2), limitar la tasa de
 * peticiones, propagar el identificador de correlacion y enrutar a los microservicios
 * mediante {@code lb://servicio} (descubrimiento + balanceo de carga).
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
