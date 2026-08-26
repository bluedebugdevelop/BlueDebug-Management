package com.bluedebug.gestion.conectores.modelo;

/**
 * Si el conector puede trabajar ahora mismo, y si no, por qué no.
 *
 * Importa que esto sea un dato y no una excepción: un panel que administra
 * varias apps tiene que seguir siendo útil cuando a una le faltan credenciales.
 * La app aparece igual en el menú, con su motivo escrito, en vez de tumbar la
 * pantalla entera o desaparecer sin explicación.
 */
public record EstadoConector(boolean disponible, String motivo) {

    public static EstadoConector listo() {
        return new EstadoConector(true, null);
    }

    public static EstadoConector sinConfigurar(String motivo) {
        return new EstadoConector(false, motivo);
    }
}
