package com.bdo.pedidos.api.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuracion del cliente del servicio de clientes (microservicio hermano).
 *
 * <p>La URL es un <b>service id</b> ({@code http://cliente-service}): la resuelve Spring Cloud
 * LoadBalancer a traves del registro de Eureka. En los tests se puede desactivar el balanceo
 * y apuntar directamente a un stub.
 */
@Validated
@ConfigurationProperties(prefix = "app.clientes")
public class ClientesProperties {

    /** Service id o URL absoluta del servicio de clientes. */
    @NotBlank
    private String url = "http://cliente-service";

    /** Si es {@code true}, la llamada se resuelve por descubrimiento + balanceo de carga. */
    private boolean loadBalanced = true;

    /** Tiempo maximo de espera de la llamada al servicio de clientes. */
    @NotNull
    private Duration timeout = Duration.ofSeconds(2);

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isLoadBalanced() {
        return loadBalanced;
    }

    public void setLoadBalanced(boolean loadBalanced) {
        this.loadBalanced = loadBalanced;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
