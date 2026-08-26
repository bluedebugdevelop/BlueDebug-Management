package com.bluedebug.gestion.conectores.modelo;

/**
 * Un número suelto de los que van en las tarjetas de arriba del panel.
 *
 * @param clave     identificador técnico ('usuarios_totales').
 * @param etiqueta  cómo se lee ('Usuarios totales').
 * @param valor     el número.
 * @param formato   'entero', 'dinero' o 'porcentaje'. El front decide cómo pintarlo.
 * @param variacion cambio respecto al periodo anterior, en tanto por uno. Null si no aplica.
 * @param detalle   texto pequeño bajo el número ('12 en los últimos 7 días').
 */
public record Metrica(
        String clave,
        String etiqueta,
        double valor,
        String formato,
        Double variacion,
        String detalle
) {
    public static Metrica entero(String clave, String etiqueta, double valor, String detalle) {
        return new Metrica(clave, etiqueta, valor, "entero", null, detalle);
    }

    public static Metrica dinero(String clave, String etiqueta, double valor, String detalle) {
        return new Metrica(clave, etiqueta, valor, "dinero", null, detalle);
    }

    public static Metrica porcentaje(String clave, String etiqueta, double valor, String detalle) {
        return new Metrica(clave, etiqueta, valor, "porcentaje", null, detalle);
    }

    /** Devuelve una copia con la variación puesta; los records son inmutables. */
    public Metrica variando(Double variacion) {
        return new Metrica(clave, etiqueta, valor, formato, variacion, detalle);
    }
}
