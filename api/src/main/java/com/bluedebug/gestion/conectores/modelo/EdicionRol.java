package com.bluedebug.gestion.conectores.modelo;

import java.util.List;

/**
 * Cómo se edita a mano el rol o el plan de una cuenta, en una app concreta.
 *
 * Cada aplicación tiene su propio vocabulario y su propia forma: en VBStats el
 * «rol» es un plan de suscripción y solo puede ser uno; en el club son roles de
 * verdad y una misma persona puede ser jugadora y entrenadora a la vez. En vez de
 * meter esa diferencia en el front con un `if` por aplicación, cada conector la
 * DECLARA aquí y la tabla de usuarios se amolda.
 *
 * Es el mismo patrón que {@link AccionAdmin}: el backend describe, el front pinta.
 * La tercera app traerá su vocabulario y funcionará sin tocar Angular.
 *
 * @param etiqueta  cómo se llama esto en esta app: «Plan», «Roles»...
 * @param multiple  si se pueden marcar varios a la vez.
 * @param opciones  los valores posibles, en el orden en que se quieren ver.
 * @param aviso     advertencia a enseñar junto al editor. Null si no hace falta.
 *                  Aquí es donde se cuenta lo que el panel no puede evitar —por
 *                  ejemplo, que la propia app pueda revertir el cambio después.
 */
public record EdicionRol(
        String etiqueta,
        boolean multiple,
        List<Opcion> opciones,
        String aviso
) {
    /**
     * @param valor    lo que se guarda.
     * @param etiqueta lo que se lee.
     * @param detalle  matiz opcional bajo la etiqueta.
     */
    public record Opcion(String valor, String etiqueta, String detalle) {

        public static Opcion de(String valor, String etiqueta) {
            return new Opcion(valor, etiqueta, null);
        }
    }

    /** Si un valor está entre los declarados. El conector valida con esto. */
    public boolean admite(String valor) {
        return opciones.stream().anyMatch(o -> o.valor().equals(valor));
    }
}
