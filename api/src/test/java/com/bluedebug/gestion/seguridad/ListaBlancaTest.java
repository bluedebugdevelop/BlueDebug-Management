package com.bluedebug.gestion.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La lista blanca de correos.
 *
 * Parece de perogrullo comprobar una comparación de cadenas, pero es la única
 * cosa que separa «entra el dueño» de «entra cualquiera con una cuenta de
 * Google», y falla por tonterías: una mayúscula en la variable de entorno, un
 * espacio detrás de una coma.
 */
class ListaBlancaTest {

    private PropiedadesSeguridad con(String... correos) {
        return new PropiedadesSeguridad("id", "secreto", List.of(correos), 12, true, List.of());
    }

    @Test
    @DisplayName("entra quien está en la lista")
    void permitidoEntra() {
        assertTrue(con("bluedebug.develop@gmail.com").permitido("bluedebug.develop@gmail.com"));
    }

    @Test
    @DisplayName("no entra quien no está")
    void otroNoEntra() {
        assertFalse(con("bluedebug.develop@gmail.com").permitido("otro@gmail.com"));
    }

    @Test
    @DisplayName("las mayúsculas y los espacios no dejan a nadie fuera")
    void mayusculasYEspacios() {
        var propiedades = con("  BlueDebug.Develop@Gmail.com  ");
        assertTrue(propiedades.permitido("bluedebug.develop@gmail.com"));
        assertTrue(propiedades.permitido("BLUEDEBUG.DEVELOP@GMAIL.COM"));
    }

    @Test
    @DisplayName("una lista vacía no deja entrar a nadie")
    void listaVaciaCierraLaPuerta() {
        // El fallo tiene que ser hacia el lado seguro: sin configuración, nadie
        // entra. Lo contrario —«si no hay lista, pasa todo el mundo»— convertiría
        // un despiste al desplegar en un panel abierto a internet.
        var propiedades = con();
        assertFalse(propiedades.permitido("bluedebug.develop@gmail.com"));
        assertFalse(propiedades.permitido(null));
    }

    @Test
    @DisplayName("las entradas en blanco de la lista no valen como comodín")
    void entradaVaciaNoEsComodin() {
        // BLUEDEBUG_PERMITIDOS="a@b.com,," produce una entrada vacía. Si esa entrada
        // se comparase, un correo vacío entraría.
        var propiedades = con("a@b.com", "", "   ");
        assertFalse(propiedades.permitido(""));
        assertTrue(propiedades.permitido("a@b.com"));
    }
}
