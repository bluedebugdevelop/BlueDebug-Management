package com.bluedebug.gestion.conectores.modelo;

import java.util.List;

/**
 * Una cosa que el administrador puede HACER sobre una app: mandar una
 * notificación, cambiarle el plan a alguien, dar de baja a un socio.
 *
 * La acción se describe aquí —qué campos pide, de qué tipo, con qué opciones— y
 * el front construye el formulario a partir de esa descripción. Es lo que hace
 * que añadir una acción nueva no toque una sola línea de Angular: se declara en
 * el conector y aparece.
 *
 * @param id           identificador, único dentro de su app.
 * @param nombre       el texto del botón.
 * @param descripcion  qué va a pasar si se pulsa.
 * @param icono        icono del set propio.
 * @param peligrosa    si hay que pedir confirmación escrita antes de ejecutarla.
 * @param textoBoton   verbo del botón de confirmar ('Enviar', 'Borrar').
 * @param campos       lo que hay que rellenar.
 */
public record AccionAdmin(
        String id,
        String nombre,
        String descripcion,
        String icono,
        boolean peligrosa,
        String textoBoton,
        List<Campo> campos
) {
    /**
     * Un campo del formulario de la acción.
     *
     * @param clave       nombre con el que llega en los parámetros.
     * @param etiqueta    lo que se lee encima del campo.
     * @param tipo        cómo se pinta.
     * @param obligatorio si no se puede enviar vacío.
     * @param maximo      longitud máxima para texto y área; 0 si no hay.
     * @param ayuda       texto pequeño bajo el campo.
     * @param opciones    valores posibles cuando el tipo es SELECCION.
     */
    public record Campo(
            String clave,
            String etiqueta,
            Tipo tipo,
            boolean obligatorio,
            int maximo,
            String ayuda,
            List<Opcion> opciones
    ) {
        public enum Tipo { TEXTO, AREA, SELECCION, NUMERO, INTERRUPTOR }

        public record Opcion(String valor, String etiqueta, String detalle) {}

        public static Campo texto(String clave, String etiqueta, int maximo, String ayuda) {
            return new Campo(clave, etiqueta, Tipo.TEXTO, true, maximo, ayuda, List.of());
        }

        public static Campo area(String clave, String etiqueta, int maximo, String ayuda) {
            return new Campo(clave, etiqueta, Tipo.AREA, true, maximo, ayuda, List.of());
        }

        public static Campo seleccion(String clave, String etiqueta, String ayuda, List<Opcion> opciones) {
            return new Campo(clave, etiqueta, Tipo.SELECCION, true, 0, ayuda, opciones);
        }
    }
}
