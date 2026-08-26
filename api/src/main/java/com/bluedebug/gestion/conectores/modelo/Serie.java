package com.bluedebug.gestion.conectores.modelo;

import java.time.LocalDate;
import java.util.List;

/**
 * Una línea de una gráfica: un nombre y unos puntos con fecha.
 *
 * Se rellenan siempre TODOS los días del rango, incluidos los que valen cero.
 * Es tentador devolver solo los días con datos —el SQL sale más corto— pero
 * entonces la gráfica miente: dos altas el lunes y dos el viernes se pintan como
 * una línea plana en vez de como dos picos con un valle en medio. De rellenar
 * los huecos se encarga {@link Rango#rellenar}.
 *
 * @param clave    identificador técnico.
 * @param etiqueta cómo se lee en la leyenda.
 * @param formato  'entero' o 'dinero'.
 * @param puntos   un punto por día del rango, en orden.
 */
public record Serie(String clave, String etiqueta, String formato, List<Punto> puntos) {

    public record Punto(LocalDate fecha, double valor) {}

    public double total() {
        return puntos.stream().mapToDouble(Punto::valor).sum();
    }
}
