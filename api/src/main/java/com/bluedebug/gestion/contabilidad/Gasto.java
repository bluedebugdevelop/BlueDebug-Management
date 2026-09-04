package com.bluedebug.gestion.contabilidad;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un gasto de la empresa: qué se pagó, cuándo, cuánto y quién puso el dinero.
 *
 * EL DINERO VA EN BigDecimal Y NO EN double. En el resto del panel el dinero es
 * double porque son cifras que se leen de Stripe para pintar una gráfica, y ahí
 * un céntimo de deriva da igual. Aquí no: de estas sumas sale cuánto le debe cada
 * socio a los otros, y un total que acabe en 1.234,5600000000002 no es un detalle
 * cosmético, es una cuenta mal hecha. Solo se pasa a double al final, al armar
 * las métricas que pinta el front.
 *
 * @param id          UUID que pone Java al insertar.
 * @param fecha       el día del pago, no el de la factura.
 * @param concepto    qué es, escrito para leer ('Railway - plan Hobby').
 * @param categoria   en qué grupo cae. Ver {@link CategoriaGasto}.
 * @param proveedor   a quién se le paga. Opcional.
 * @param importe     lo que salió de la cuenta, IVA incluido.
 * @param iva         cuánto de ese importe era IVA. Cero si no lo lleva o no se sabe.
 * @param pagadoPor   cuál de los socios lo pagó de su bolsillo. La lista la fija
 *                    {@code bluedebug.contabilidad.socios}.
 * @param app         a qué aplicación se imputa, con el id del conector, o null si
 *                    es un gasto de la empresa en general (gestoría, cuotas).
 * @param recurrencia si se repite, para poder calcular el coste fijo mensual.
 * @param nota        lo que no cabe en el concepto. Opcional.
 * @param creadoPor   el correo de quien lo apuntó en el panel. No tiene por qué
 *                    ser el mismo que lo pagó.
 * @param creadoEn    cuándo se apuntó.
 */
public record Gasto(
        String id,
        LocalDate fecha,
        String concepto,
        CategoriaGasto categoria,
        String proveedor,
        BigDecimal importe,
        BigDecimal iva,
        String pagadoPor,
        String app,
        Recurrencia recurrencia,
        String nota,
        String creadoPor,
        Instant creadoEn
) {
    /** Lo que se pagó sin el IVA: la parte que es coste de verdad. */
    public BigDecimal base() {
        return importe.subtract(iva);
    }
}
