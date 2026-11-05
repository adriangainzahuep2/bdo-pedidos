package com.bdo.pedidos.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Servidor de configuracion (Spring Cloud Config).
 *
 * <p>Sirve la configuracion desde un repositorio local (perfil {@code native}) y permite
 * cifrar los valores sensibles con JCE: el token del proveedor logistico se guarda en el
 * repositorio como {@code {cipher}...} y el servidor lo descifra con la clave que tiene en
 * el entorno. La clave de cifrado <b>nunca</b> esta en el repositorio de configuracion.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
