package com.bdo.pedidos.dominio.puerto;

import com.bdo.pedidos.dominio.modelo.ResultadoTarifa;
import com.bdo.pedidos.dominio.modelo.SolicitudTarifa;
import com.bdo.pedidos.dominio.modelo.Tarifa;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida hacia el proveedor logistico externo.
 *
 * <p>El dominio no conoce HTTP: define el contrato en terminos de negocio y deja que la
 * infraestructura lo implemente (WebClient reactivo o RestTemplate bloqueante).
 *
 * <p>El contrato reactivo ({@link Mono}) es una decision consciente: obliga a que TODOS
 * los adaptadores sean no bloqueantes y evita el error clasico de meter una llamada
 * bloqueante en el event loop de Netty. Ver docs/DECISIONES.md.
 */
public interface ProveedorTarifasPort {

    /**
     * Calcula la tarifa de envio.
     *
     * @throws com.bdo.pedidos.dominio.error.ProveedorHttpException        si el proveedor responde con error
     * @throws com.bdo.pedidos.dominio.error.ProveedorTimeoutException     si se agota el tiempo disponible
     * @throws com.bdo.pedidos.dominio.error.ProveedorNoDisponibleException si el circuito esta abierto o el limite de peticiones esta saturado
     * @throws com.bdo.pedidos.dominio.error.ProveedorRespuestaInvalidaException si la respuesta no cumple el contrato
     */
    Mono<ResultadoTarifa> calcularTarifa(SolicitudTarifa solicitud);
}
