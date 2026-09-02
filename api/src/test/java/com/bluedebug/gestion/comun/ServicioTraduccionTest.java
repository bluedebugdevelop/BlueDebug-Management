package com.bluedebug.gestion.comun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qué se hace con lo que devuelve el traductor.
 *
 * No se llama a la API en ninguna de estas pruebas: lo que se comprueba es la
 * regla que protege de su único fallo grave. Un modelo puede devolver una línea
 * de más o de menos, y emparejar por posición una lista descuadrada pondría el
 * titular equivocado en el idioma equivocado. Eso no lo ve nadie hasta que lo lee
 * alguien que hable ese idioma, que es justo lo que no se puede permitir cuando
 * el texto sale publicado en cuatro países.
 */
class ServicioTraduccionTest {

    /** Sin clave: el constructor no llama a nadie y `recoger` no la necesita. */
    private final ServicioTraduccion servicio =
            new ServicioTraduccion(new PropiedadesTraduccion(null, null));

    @Test
    @DisplayName("sin clave no se traduce, y se dice por qué")
    void sinClave() {
        assertFalse(servicio.configurado());

        var resultado = servicio.traducir(List.of("Una novedad"), "contexto", 90);

        assertFalse(resultado.correcto());
        assertTrue(resultado.mensaje().contains("ANTHROPIC_API_KEY"), resultado.mensaje());
    }

    @Test
    @DisplayName("las tres lenguas con el número de líneas correcto entran enteras")
    void todoCuadra() {
        var resultado = servicio.recoger(new ServicioTraduccion.Traduccion(
                List.of("One", "Two"), List.of("Un", "Deux"), List.of("Um", "Dois")), 2);

        assertTrue(resultado.correcto());
        assertEquals(3, resultado.porIdioma().size());
        assertEquals(List.of("Un", "Deux"), resultado.porIdioma().get("fr"));
    }

    @Test
    @DisplayName("la lengua que viene descuadrada se descarta y las demás se quedan")
    void unaDescuadrada() {
        var resultado = servicio.recoger(new ServicioTraduccion.Traduccion(
                List.of("One", "Two"), List.of("Un"), List.of("Um", "Dois")), 2);

        assertTrue(resultado.correcto());
        assertEquals(2, resultado.porIdioma().size());
        assertFalse(resultado.porIdioma().containsKey("fr"));
        assertTrue(resultado.mensaje().contains("fr"), resultado.mensaje());
    }

    @Test
    @DisplayName("una lengua con una línea vacía tampoco vale")
    void lineaVacia() {
        var resultado = servicio.recoger(new ServicioTraduccion.Traduccion(
                List.of("One", "  "), List.of("Un", "Deux"), List.of("Um", "Dois")), 2);

        assertFalse(resultado.porIdioma().containsKey("en"));
        assertEquals(2, resultado.porIdioma().size());
    }

    @Test
    @DisplayName("si no cuadra ninguna, es un fallo y no un éxito vacío")
    void ningunaCuadra() {
        var resultado = servicio.recoger(new ServicioTraduccion.Traduccion(
                List.of("One"), null, List.of()), 2);

        assertFalse(resultado.correcto());
        assertTrue(resultado.porIdioma().isEmpty());
    }
}
