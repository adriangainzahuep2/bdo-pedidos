package com.bdo.pedidos.dominio.modelo;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Maquina de estados del pedido. Es la unica fuente de verdad sobre que transiciones
 * son legales; la aplicacion NO puede saltarsela.
 *
 * <pre>
 *   PENDIENTE  --confirmar-->   CONFIRMANDO   --ok-->    CONFIRMADO   (terminal)
 *      ^                             |
 *      |  --fallo del proveedor-------|
 *      |                             |
 *      +--cancelar--> CANCELADO (terminal)
 * </pre>
 *
 * <ul>
 *   <li><b>PENDIENTE</b>: creado, pendiente de confirmar. Es el unico estado en el que el
 *       cliente puede solicitar la confirmacion o cancelar el pedido.</li>
 *   <li><b>CONFIRMANDO</b>: estado transitorio. La peticion ha tomado el "lock" logico del
 *       pedido (mediante un UPDATE condicional en base de datos) y esta consultando al
 *       proveedor externo. Impide que dos confirmaciones simultaneas llamen al proveedor.
 *       Si el proveedor falla, el pedido vuelve a PENDIENTE (reintentable).</li>
 *   <li><b>CONFIRMADO</b>: el coste de envio se ha obtenido y el total esta calculado. Terminal.</li>
 *   <li><b>CANCELADO</b>: el cliente ha cancelado el pedido antes de confirmarlo. Terminal.</li>
 * </ul>
 */
public enum EstadoPedido {

    PENDIENTE,
    CONFIRMANDO,
    CONFIRMADO,
    CANCELADO;

    private static final Map<EstadoPedido, Set<EstadoPedido>> TRANSICIONES = Map.of(
            PENDIENTE, EnumSet.of(CONFIRMANDO, CANCELADO),
            CONFIRMANDO, EnumSet.of(CONFIRMADO, PENDIENTE),
            CONFIRMADO, EnumSet.noneOf(EstadoPedido.class),
            CANCELADO, EnumSet.noneOf(EstadoPedido.class));

    /** @return true si la transicion a {@code destino} esta permitida desde este estado. */
    public boolean puedeTransicionarA(EstadoPedido destino) {
        return destino != null && TRANSICIONES.get(this).contains(destino);
    }

    /** @return los estados alcanzables desde este estado. */
    public Set<EstadoPedido> transicionesPermitidas() {
        return Set.copyOf(TRANSICIONES.get(this));
    }

    /** @return true si no admite ninguna transicion posterior. */
    public boolean esTerminal() {
        return TRANSICIONES.get(this).isEmpty();
    }

    /** @return true si el estado es transitorio (puede volver a PENDIENTE). */
    public boolean esTransitorio() {
        return this == CONFIRMANDO;
    }
}
