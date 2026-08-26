package com.bluedebug.gestion.conectores.modelo;

import java.util.List;
import java.util.Map;

/**
 * Una tabla cualquiera que una app quiera enseñar en su pestaña de detalle.
 *
 * Existe para no tener que romper la genericidad del panel cada vez que una app
 * tiene algo propio que mostrar. VBStats quiere ver su historial de avisos y CVO
 * la lista de equipos; ninguna de las dos cosas encaja en «usuarios» ni en
 * «métricas», y montar una pantalla a medida para cada una es justo lo que hace
 * que un panel deje de escalar a la tercera app.
 *
 * @param clave    identificador técnico.
 * @param titulo   lo que se lee encima.
 * @param columnas qué columnas y en qué orden.
 * @param filas    cada fila es un mapa clave → valor; las claves son las de las columnas.
 * @param vacia    qué poner cuando no hay filas.
 */
public record Tabla(
        String clave,
        String titulo,
        List<Columna> columnas,
        List<Map<String, Object>> filas,
        String vacia
) {
    /**
     * @param clave   nombre del campo en la fila.
     * @param titulo  cabecera.
     * @param formato 'texto', 'entero', 'dinero', 'fecha'. El front lo usa para
     *                alinear y para dar formato; por defecto, texto.
     */
    public record Columna(String clave, String titulo, String formato) {

        public static Columna texto(String clave, String titulo) {
            return new Columna(clave, titulo, "texto");
        }

        public static Columna fecha(String clave, String titulo) {
            return new Columna(clave, titulo, "fecha");
        }

        public static Columna entero(String clave, String titulo) {
            return new Columna(clave, titulo, "entero");
        }
    }
}
