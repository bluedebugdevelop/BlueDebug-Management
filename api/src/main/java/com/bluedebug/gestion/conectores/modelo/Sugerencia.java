package com.bluedebug.gestion.conectores.modelo;

import java.util.Map;

/**
 * Lo que un asistente propone para rellenar un formulario de acción.
 *
 * No escribe nada en ninguna parte: devuelve valores para unos campos, el front
 * los mete en el formulario y quien los ve decide si los deja, los corrige o los
 * borra antes de ejecutar la acción. Esa es toda la garantía que hace falta para
 * poder enchufar aquí algo tan poco determinista como un modelo de lenguaje: lo
 * que se guarda es siempre lo que había en pantalla cuando se pulsó el botón.
 *
 * @param correcto si se pudo proponer algo.
 * @param mensaje  qué contar debajo del botón, salga bien o mal. También se usa
 *                 para los éxitos a medias: «traducido, menos el francés».
 * @param valores  clave del campo → texto propuesto. Los campos que no salgan
 *                 aquí se quedan como estuvieran.
 */
public record Sugerencia(boolean correcto, String mensaje, Map<String, String> valores) {

    public static Sugerencia error(String mensaje) {
        return new Sugerencia(false, mensaje, Map.of());
    }

    public static Sugerencia de(String mensaje, Map<String, String> valores) {
        return new Sugerencia(true, mensaje, Map.copyOf(valores));
    }
}
