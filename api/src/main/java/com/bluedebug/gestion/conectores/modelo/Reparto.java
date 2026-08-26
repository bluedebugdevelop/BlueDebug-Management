package com.bluedebug.gestion.conectores.modelo;

import java.util.List;

/**
 * Un reparto por categorías, de los que se pintan como donut o como barras:
 * usuarios por plan, dispositivos por plataforma, gente por equipo.
 */
public record Reparto(String clave, String etiqueta, List<Trozo> trozos) {

    /** El color es opcional: si va a null, el front tira de su paleta por orden. */
    public record Trozo(String etiqueta, double valor, String color) {

        public static Trozo de(String etiqueta, double valor) {
            return new Trozo(etiqueta, valor, null);
        }
    }

    public double total() {
        return trozos.stream().mapToDouble(Trozo::valor).sum();
    }
}
