package com.bluedebug.gestion.conectores.modelo;

import java.time.Instant;
import java.util.List;

/**
 * El dinero de una app en un periodo.
 *
 * Todo va en euros como decimal ya convertido: Stripe habla en céntimos y
 * mezclar las dos unidades en el mismo objeto es la forma más rápida de acabar
 * enseñando una facturación cien veces mayor de la real. La conversión se hace
 * en el conector, y de aquí para arriba solo hay euros.
 *
 * @param moneda      código ISO en minúsculas ('eur').
 * @param facturado   cobrado en el periodo, ya descontadas las devoluciones.
 * @param devuelto    lo devuelto en el periodo, en positivo.
 * @param recurrente  facturación mensual recurrente estimada a día de hoy.
 * @param suscriptores suscripciones activas ahora mismo.
 * @param porDia      serie diaria de lo facturado.
 * @param porPlan     reparto del dinero por plan.
 * @param movimientos los últimos cobros, para la tabla del final.
 */
public record Ingresos(
        String moneda,
        double facturado,
        double devuelto,
        double recurrente,
        int suscriptores,
        Serie porDia,
        Reparto porPlan,
        List<Movimiento> movimientos
) {
    /**
     * Un cobro suelto.
     *
     * @param id      identificador en la pasarela.
     * @param fecha   cuándo se cobró.
     * @param email   a quién, si se sabe.
     * @param importe en euros; negativo si es una devolución.
     * @param estado  'pagado', 'devuelto', 'fallido'.
     * @param origen  'stripe', 'apple', 'google'.
     */
    public record Movimiento(
            String id,
            Instant fecha,
            String email,
            double importe,
            String estado,
            String origen
    ) {}
}
