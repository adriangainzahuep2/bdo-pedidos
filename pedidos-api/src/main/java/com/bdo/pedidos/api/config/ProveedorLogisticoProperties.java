package com.bdo.pedidos.api.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuracion del proveedor logistico externo.
 *
 * <p>El token <b>no vive en el codigo fuente</b>: se inyecta desde el exterior
 * (variable de entorno / Spring Cloud Config cifrado). Si falta, la aplicacion no arranca
 * (fail-fast en el arranque, no un 500 en la primera llamada).
 *
 * <p>Valores por defecto y su justificacion (detalle en docs/DECISIONES.md):
 * <ul>
 *   <li>{@code connect-timeout = 1 s}: si el proveedor no acepta la conexion, se falla ya.</li>
 *   <li>{@code timeout-por-intento = 6 s}: el proveedor puede tardar hasta 5 s, asi que el
 *       limite por intento va algo por encima de ese maximo (1 s de margen).</li>
 *   <li>{@code timeout-global = 8 s}: presupuesto total de la operacion, incluidos los
 *       reintentos y las esperas entre ellos.</li>
 *   <li>{@code max-intentos = 3}: 1 intento inicial + 2 reintentos, solo ante fallos
 *       transitorios (503/429 y errores de conexion). Los timeouts <b>no</b> se reintentan:
 *       como una respuesta legitima puede tardar 5 s, reintentar un timeout reventaria el
 *       presupuesto de la peticion.</li>
 *   <li>{@code backoff-inicial = 200 ms} con multiplicador 2 y jitter del 50 %: evita que
 *       las N instancias del servicio reintenten a la vez en el mismo instante (thundering herd).</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "app.proveedor")
public class ProveedorLogisticoProperties {

    /** Modo del adaptador: {@code reactivo} (WebClient) o {@code bloqueante} (RestTemplate). */
    public static final String MODO_REACTIVO = "reactivo";
    public static final String MODO_BLOQUEANTE = "bloqueante";

    /** URL completa del endpoint de tarifas del proveedor. */
    @NotBlank
    private String url;

    /** Token de acceso. NUNCA en el codigo fuente: llega del exterior. */
    @NotBlank
    private String token;

    /** Modo del adaptador de salida al proveedor. */
    @NotNull
    private String modo = MODO_REACTIVO;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(1);

    @NotNull
    private Duration timeoutPorIntento = Duration.ofSeconds(6);

    @NotNull
    private Duration timeoutGlobal = Duration.ofSeconds(8);

    @Min(1)
    private int maxIntentos = 3;

    @NotNull
    private Duration backoffInicial = Duration.ofMillis(200);

    private double backoffMultiplicador = 2.0d;

    /** Jitter aplicado a la espera entre reintentos (0 = sin jitter, 0.5 = +/-50 %). */
    private double backoffJitter = 0.5d;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getModo() {
        return modo;
    }

    public void setModo(String modo) {
        this.modo = modo;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getTimeoutPorIntento() {
        return timeoutPorIntento;
    }

    public void setTimeoutPorIntento(Duration timeoutPorIntento) {
        this.timeoutPorIntento = timeoutPorIntento;
    }

    public Duration getTimeoutGlobal() {
        return timeoutGlobal;
    }

    public void setTimeoutGlobal(Duration timeoutGlobal) {
        this.timeoutGlobal = timeoutGlobal;
    }

    public int getMaxIntentos() {
        return maxIntentos;
    }

    public void setMaxIntentos(int maxIntentos) {
        this.maxIntentos = maxIntentos;
    }

    public Duration getBackoffInicial() {
        return backoffInicial;
    }

    public void setBackoffInicial(Duration backoffInicial) {
        this.backoffInicial = backoffInicial;
    }

    public double getBackoffMultiplicador() {
        return backoffMultiplicador;
    }

    public void setBackoffMultiplicador(double backoffMultiplicador) {
        this.backoffMultiplicador = backoffMultiplicador;
    }

    public double getBackoffJitter() {
        return backoffJitter;
    }

    public void setBackoffJitter(double backoffJitter) {
        this.backoffJitter = backoffJitter;
    }

    /** El presupuesto global debe dejar hacer al menos un intento completo. */
    @AssertTrue(message = "app.proveedor.timeout-global debe ser mayor o igual que timeout-por-intento")
    public boolean isPresupuestoCoherente() {
        return timeoutGlobal == null || timeoutPorIntento == null || timeoutGlobal.compareTo(timeoutPorIntento) >= 0;
    }

    /** Multiplicador y jitter dentro de rangos con sentido. */
    @AssertTrue(message = "app.proveedor.backoff-multiplicador debe ser >= 1 y backoff-jitter estar en [0, 1)")
    public boolean isBackoffCoherente() {
        return backoffMultiplicador >= 1.0d && backoffJitter >= 0.0d && backoffJitter < 1.0d;
    }
}
