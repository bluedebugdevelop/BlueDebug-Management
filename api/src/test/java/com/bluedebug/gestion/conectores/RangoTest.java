package com.bluedebug.gestion.conectores;

import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Serie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El rango de fechas y el relleno de las series.
 *
 * Aquí es donde vive el error silencioso más fácil de cometer en todo el panel:
 * agrupar por día sin tener en cuenta el huso horario, o dejar los días sin datos
 * fuera de la serie. Ninguna de las dos cosas da error; las dos hacen que la
 * gráfica cuente algo que no pasó.
 */
class RangoTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Test
    @DisplayName("un rango de 7 días son 7 días, contando hoy")
    void longitud() {
        Rango rango = Rango.ultimosDias(7);
        assertEquals(7, rango.dias());
        assertEquals(7, rango.todosLosDias().size());
        assertEquals(LocalDate.now(MADRID), rango.hasta());
    }

    @Test
    @DisplayName("el periodo anterior tiene la misma longitud y acaba justo antes")
    void anterior() {
        Rango rango = new Rango(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 19), MADRID);
        Rango antes = rango.anterior();

        assertEquals(rango.dias(), antes.dias());
        assertEquals(LocalDate.of(2026, 8, 9), antes.hasta());
        assertEquals(LocalDate.of(2026, 7, 31), antes.desde());
    }

    @Test
    @DisplayName("rellenar pone un cero en los días sin datos")
    void rellenaLosHuecos() {
        // Es la razón de ser del método: con solo los días que tienen datos, dos
        // altas el lunes y dos el viernes se pintarían como una línea plana en vez
        // de como dos picos con un valle en medio.
        Rango rango = new Rango(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5), MADRID);

        Serie serie = rango.rellenar("altas", "Altas", "entero", Map.of(
                LocalDate.of(2026, 8, 1), 2d,
                LocalDate.of(2026, 8, 5), 3d));

        assertEquals(5, serie.puntos().size());
        assertEquals(2d, serie.puntos().get(0).valor());
        assertEquals(0d, serie.puntos().get(1).valor());
        assertEquals(0d, serie.puntos().get(2).valor());
        assertEquals(0d, serie.puntos().get(3).valor());
        assertEquals(3d, serie.puntos().get(4).valor());
        assertEquals(5d, serie.total());
    }

    @Test
    @DisplayName("la madrugada española cuenta en su día, no en el anterior")
    void husoHorario() {
        // 00:30 del 12 de agosto en Madrid son las 22:30 del día 11 en UTC. Agrupando
        // en UTC —que es lo que hace un GROUP BY DATE() sobre un TIMESTAMP— esa alta
        // se contaría el día anterior. En un panel que se mira por la mañana, eso se
        // nota.
        Rango rango = new Rango(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), MADRID);
        Instant madrugada = LocalDate.of(2026, 8, 12).atStartOfDay(MADRID).toInstant().plusSeconds(1800);

        assertEquals(LocalDate.of(2026, 8, 12), rango.diaDe(madrugada));
    }

    @Test
    @DisplayName("el fin del rango es el arranque del día siguiente, no las 23:59")
    void finExclusivo() {
        // Con las 23:59:59 se pierde lo que pase en el último segundo del día. Con el
        // arranque del día siguiente y una comparación `<`, no se pierde nada ni se
        // cuela nada.
        Rango rango = new Rango(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), MADRID);
        assertEquals(LocalDate.of(2026, 8, 2).atStartOfDay(MADRID).toInstant(), rango.fin());
    }
}
