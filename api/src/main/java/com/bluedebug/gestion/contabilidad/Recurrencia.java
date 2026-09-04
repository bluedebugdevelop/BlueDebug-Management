package com.bluedebug.gestion.contabilidad;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Cada cuánto se paga un gasto.
 *
 * No genera apuntes automáticos ni nada parecido: cada pago se apunta cuando se
 * paga. Sirve para una pregunta concreta y bastante importante, que es «cuánto
 * cuesta tener esto en pie al mes» — y para eso hay que saber que los 99 € de
 * Apple son de un año entero y los de Railway son de un mes.
 *
 * Ver {@code ServicioContabilidad.costeFijoMensual}, que es quien la usa.
 */
public enum Recurrencia {

    UNICO("Pago único", 0),
    MENSUAL("Todos los meses", 1),
    ANUAL("Una vez al año", 12);

    private final String etiqueta;

    /** En cuántos meses se reparte el importe. Cero si no es un gasto que se repita. */
    private final int meses;

    Recurrencia(String etiqueta, int meses) {
        this.etiqueta = etiqueta;
        this.meses = meses;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /** Lo que este gasto supone al mes. Cero para los pagos únicos. */
    public java.math.BigDecimal alMes(java.math.BigDecimal importe) {
        if (meses == 0) {
            return java.math.BigDecimal.ZERO;
        }
        return importe.divide(java.math.BigDecimal.valueOf(meses), 2, java.math.RoundingMode.HALF_UP);
    }

    public record Opcion(String valor, String etiqueta) {}

    public static List<Opcion> opciones() {
        return Arrays.stream(values()).map(r -> new Opcion(r.name(), r.etiqueta())).toList();
    }

    public static Optional<Recurrencia> de(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(r -> r.name().equalsIgnoreCase(valor.trim()))
                .findFirst();
    }
}
