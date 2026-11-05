package com.bdo.pedidos.dominio.modelo;

import com.bdo.pedidos.dominio.error.DatosInvalidosException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor inmutable que representa una cantidad monetaria.
 *
 * <p>Decisiones (ver docs/DECISIONES.md):
 * <ul>
 *   <li>Nunca {@code double}/{@code float}: los importes monetarios se representan con
 *       {@link BigDecimal} para evitar errores de redondeo binario (p. ej. 0.1 + 0.2 != 0.3).</li>
 *   <li>Escala explicita: los <b>importes</b> usan 2 decimales (centimos) y los
 *       <b>precios unitarios</b> 4 decimales, que es la precision con la que llega el
 *       catalogo; el redondeo a centimos se aplica en la linea, no en la suma final.</li>
 *   <li>Modo de redondeo {@link RoundingMode#HALF_UP} (redondeo comercial, habitual en
 *       facturacion en Espana). Se documenta explicitamente frente a HALF_EVEN (contabilidad
 *       bancaria) porque en un importe a pagar el cliente espera que 0,005 se redondee hacia
 *       arriba y no hacia el par.</li>
 *   <li>La moneda se transporta siempre: sumar importes de monedas distintas es un error de
 *       programacion y se rechaza, no se resuelve de forma implicita.</li>
 * </ul>
 */
public final class Dinero {

    /** Escala de los importes monetarios (centimos). */
    public static final int ESCALA_IMPORTE = 2;

    /** Escala de los precios unitarios de linea. */
    public static final int ESCALA_PRECIO_UNITARIO = 4;

    /** Moneda por defecto del catalogo. */
    public static final String MONEDA_EUR = "EUR";

    private static final Dinero CERO_EUR = new Dinero(BigDecimal.ZERO.setScale(ESCALA_IMPORTE), MONEDA_EUR, ESCALA_IMPORTE);

    private final BigDecimal importe;
    private final String moneda;
    private final int escala;

    private Dinero(BigDecimal importe, String moneda, int escala) {
        this.importe = importe;
        this.moneda = moneda;
        this.escala = escala;
    }

    /** Cero euros. */
    public static Dinero cero() {
        return CERO_EUR;
    }

    /**
     * Crea un importe monetario con 2 decimales.
     *
     * @throws DatosInvalidosException si el importe es nulo, negativo o la moneda no es valida
     */
    public static Dinero importe(BigDecimal valor, String moneda) {
        return crear(valor, moneda, ESCALA_IMPORTE);
    }

    /**
     * Crea un precio unitario con 4 decimales.
     *
     * @throws DatosInvalidosException si el precio es nulo o negativo
     */
    public static Dinero precioUnitario(BigDecimal valor, String moneda) {
        return crear(valor, moneda, ESCALA_PRECIO_UNITARIO);
    }

    private static Dinero crear(BigDecimal valor, String moneda, int escala) {
        if (valor == null) {
            throw new DatosInvalidosException("El importe monetario no puede ser nulo");
        }
        if (moneda == null || moneda.isBlank()) {
            throw new DatosInvalidosException("La moneda no puede ser nula ni vacia");
        }
        if (valor.signum() < 0) {
            throw new DatosInvalidosException("El importe monetario no puede ser negativo: " + valor.toPlainString());
        }
        if (valor.scale() > escala) {
            throw new DatosInvalidosException(String.format(
                    "El importe %s tiene mas de %d decimales y no se puede representar en escala %d",
                    valor.toPlainString(), escala, escala));
        }
        return new Dinero(valor.setScale(escala, RoundingMode.HALF_UP), moneda.toUpperCase(java.util.Locale.ROOT), escala);
    }

    /**
     * Redondea a la escala de importes (2 decimales) usando HALF_UP.
     * Se usa al convertir un precio unitario (4 decimales) en un importe de linea.
     */
    public Dinero aImporte() {
        if (escala == ESCALA_IMPORTE) {
            return this;
        }
        return new Dinero(importe.setScale(ESCALA_IMPORTE, RoundingMode.HALF_UP), moneda, ESCALA_IMPORTE);
    }

    /**
     * Suma dos importes. Ambos deben expresar la misma moneda.
     *
     * @throws DatosInvalidosException si las monedas difieren
     */
    public Dinero mas(Dinero otro) {
        Objects.requireNonNull(otro, "otro");
        if (!moneda.equalsIgnoreCase(otro.moneda)) {
            throw new DatosInvalidosException(
                    "No se pueden sumar importes de monedas distintas: " + moneda + " + " + otro.moneda);
        }
        int escalaResultado = Math.max(escala, otro.escala);
        BigDecimal suma = importe.add(otro.importe).setScale(escalaResultado, RoundingMode.HALF_UP);
        return new Dinero(suma, moneda, escalaResultado);
    }

    /** Multiplicacion por una cantidad entera (unidades), conservando la escala. */
    public Dinero porUnidades(int unidades) {
        if (unidades < 0) {
            throw new DatosInvalidosException("No se puede multiplicar un importe por una cantidad negativa");
        }
        return new Dinero(importe.multiply(BigDecimal.valueOf(unidades)), moneda, escala);
    }

    public BigDecimal importe() {
        return importe;
    }

    public String moneda() {
        return moneda;
    }

    public int escala() {
        return escala;
    }

    public boolean esCero() {
        return importe.signum() == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Dinero otro)) {
            return false;
        }
        return importe.compareTo(otro.importe) == 0 && moneda.equalsIgnoreCase(otro.moneda);
    }

    @Override
    public int hashCode() {
        return Objects.hash(importe.stripTrailingZeros(), moneda.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public String toString() {
        return importe.toPlainString() + " " + moneda;
    }
}
