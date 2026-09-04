package com.bluedebug.gestion.contabilidad;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * En qué se va el dinero.
 *
 * Es una lista cerrada y no texto libre por lo mismo que los socios: con texto
 * libre, «Railway», «railway» y «servidor» son tres categorías distintas y el
 * donut de reparto deja de significar nada al tercer mes.
 *
 * Están pensadas para lo que gasta BlueDebug de verdad, no para un plan contable
 * general: el 90 % de los apuntes van a caer en las cuatro primeras. Añadir una
 * es una línea aquí y nada más — el formulario y las gráficas del front se
 * construyen con lo que declara este enum.
 *
 * El color va en el enum, y no en el front, para que el mismo concepto salga del
 * mismo color en el donut, en la tabla y en cualquier gráfica futura.
 */
public enum CategoriaGasto {

    BASE_DATOS("Bases de datos", "#0ea5e9"),
    HOSTING("Servidores y despliegue", "#2196f3"),
    TIENDAS("Licencias de tienda", "#a78bfa"),
    DOMINIOS("Dominios y correo", "#14b8a6"),
    SOFTWARE("Software y suscripciones", "#f97316"),
    IA("APIs e IA", "#22c55e"),
    CUOTAS("Cuotas y gestoría", "#eab308"),
    IMPUESTOS("Impuestos", "#f43f5e"),
    HARDWARE("Equipamiento", "#94a3b8"),
    MARKETING("Marketing y diseño", "#ec4899"),
    OTROS("Otros", "#8fa0c2");

    private final String etiqueta;
    private final String color;

    CategoriaGasto(String etiqueta, String color) {
        this.etiqueta = etiqueta;
        this.color = color;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public String color() {
        return color;
    }

    /** Lo que se manda al front para pintar el desplegable. */
    public record Opcion(String valor, String etiqueta, String color) {}

    public static List<Opcion> opciones() {
        return Arrays.stream(values())
                .map(c -> new Opcion(c.name(), c.etiqueta(), c.color()))
                .toList();
    }

    public static Optional<CategoriaGasto> de(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(valor.trim()))
                .findFirst();
    }
}
