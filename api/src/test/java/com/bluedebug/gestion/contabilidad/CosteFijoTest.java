package com.bluedebug.gestion.contabilidad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * «Cuánto cuesta tener esto en pie al mes».
 *
 * Es la cifra que más fácil sale mal, porque la trampa no se ve: doce recibos de
 * Railway apuntados a lo largo del año son UN coste fijo, no doce. Sin esta
 * prueba, el número crece solo según se van metiendo apuntes y nadie sospecha
 * hasta que dice que la empresa cuesta mil euros al mes.
 */
class CosteFijoTest {

    /** El servicio sin dependencias: el cálculo no toca ni la base ni los conectores. */
    private final ServicioContabilidad servicio = new ServicioContabilidad(null, null, null, null);

    @Test
    @DisplayName("lo anual se reparte entre doce y lo mensual cuenta entero")
    void prorrateo() {
        BigDecimal alMes = servicio.costeFijoMensual(List.of(
                gasto("Licencia Apple Developer", "99.00", Recurrencia.ANUAL, "2026-01-15"),
                gasto("Railway", "20.00", Recurrencia.MENSUAL, "2026-08-01")));

        // 99/12 = 8,25 + 20 = 28,25
        assertEquals(new BigDecimal("28.25"), alMes);
    }

    @Test
    @DisplayName("doce recibos del mismo servicio son un coste fijo, no doce")
    void unSoloRecibosPorConcepto() {
        BigDecimal alMes = servicio.costeFijoMensual(List.of(
                gasto("Railway", "20.00", Recurrencia.MENSUAL, "2026-01-01"),
                gasto("Railway", "20.00", Recurrencia.MENSUAL, "2026-02-01"),
                gasto("Railway", "22.00", Recurrencia.MENSUAL, "2026-03-01")));

        // Y vale el ÚLTIMO, no el primero: si el servicio ha subido de precio, el
        // coste fijo de hoy es el nuevo.
        assertEquals(new BigDecimal("22.00"), alMes);
    }

    @Test
    @DisplayName("el mismo concepto escrito con otras mayúsculas no cuenta dos veces")
    void mayusculasIndiferentes() {
        BigDecimal alMes = servicio.costeFijoMensual(List.of(
                gasto("Railway", "20.00", Recurrencia.MENSUAL, "2026-01-01"),
                gasto("  railway ", "20.00", Recurrencia.MENSUAL, "2026-02-01")));

        assertEquals(new BigDecimal("20.00"), alMes);
    }

    @Test
    @DisplayName("los pagos únicos no son coste fijo")
    void losUnicosNoCuentan() {
        BigDecimal alMes = servicio.costeFijoMensual(List.of(
                gasto("Portátil de Diego", "1400.00", Recurrencia.UNICO, "2026-03-10"),
                gasto("Railway", "20.00", Recurrencia.MENSUAL, "2026-03-01")));

        assertEquals(new BigDecimal("20.00"), alMes);
    }

    @Test
    @DisplayName("sin gastos recurrentes el coste fijo es cero")
    void sinRecurrentes() {
        BigDecimal alMes = servicio.costeFijoMensual(List.of(
                gasto("Gestoría, alta de sociedad", "300.00", Recurrencia.UNICO, "2026-01-02")));

        assertEquals(0, alMes.signum());
    }

    private Gasto gasto(String concepto, String importe, Recurrencia recurrencia, String fecha) {
        return new Gasto(
                java.util.UUID.randomUUID().toString(),
                LocalDate.parse(fecha),
                concepto,
                CategoriaGasto.HOSTING,
                null,
                new BigDecimal(importe),
                BigDecimal.ZERO,
                "Adrián Estrada",
                null,
                recurrencia,
                null,
                "adrian@bluedebug.com",
                Instant.now());
    }
}
