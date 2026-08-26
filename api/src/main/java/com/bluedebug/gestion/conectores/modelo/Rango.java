package com.bluedebug.gestion.conectores.modelo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El periodo que se está mirando, en días naturales y en la zona horaria de
 * casa.
 *
 * Lo de la zona horaria no es un detalle: las bases de datos guardan UTC, y si
 * se agrupa por día en UTC, todo lo que pasa entre medianoche y las dos de la
 * mañana en España cae en el día anterior. En un panel que se mira por la mañana
 * eso se nota, así que la conversión se hace siempre aquí y una sola vez.
 *
 * @param desde primer día incluido.
 * @param hasta último día incluido.
 * @param zona  zona con la que se decide a qué día pertenece cada instante.
 */
public record Rango(LocalDate desde, LocalDate hasta, ZoneId zona) {

    public static final ZoneId ZONA_CASA = ZoneId.of("Europe/Madrid");

    /** Los últimos {@code dias} días contando hoy. */
    public static Rango ultimosDias(int dias) {
        LocalDate hoy = LocalDate.now(ZONA_CASA);
        return new Rango(hoy.minusDays(Math.max(1, dias) - 1L), hoy, ZONA_CASA);
    }

    /** El periodo de la misma longitud inmediatamente anterior, para comparar. */
    public Rango anterior() {
        long dias = dias();
        return new Rango(desde.minusDays(dias), desde.minusDays(1), zona);
    }

    public long dias() {
        return ChronoUnit.DAYS.between(desde, hasta) + 1;
    }

    /** El instante en que empieza el rango, para meterlo en una consulta. */
    public Instant inicio() {
        return desde.atStartOfDay(zona).toInstant();
    }

    /** El instante en que acaba: el arranque del día siguiente, exclusivo. */
    public Instant fin() {
        return hasta.plusDays(1).atStartOfDay(zona).toInstant();
    }

    public List<LocalDate> todosLosDias() {
        List<LocalDate> dias = new ArrayList<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            dias.add(d);
        }
        return dias;
    }

    public LocalDate diaDe(Instant instante) {
        return instante.atZone(zona).toLocalDate();
    }

    /**
     * Convierte un mapa disperso de día → valor en una serie completa, poniendo
     * un cero en cada día que falte. Ver el porqué en {@link Serie}.
     */
    public Serie rellenar(String clave, String etiqueta, String formato, Map<LocalDate, Double> valores) {
        List<Serie.Punto> puntos = new ArrayList<>();
        for (LocalDate dia : todosLosDias()) {
            puntos.add(new Serie.Punto(dia, valores.getOrDefault(dia, 0d)));
        }
        return new Serie(clave, etiqueta, formato, puntos);
    }
}
