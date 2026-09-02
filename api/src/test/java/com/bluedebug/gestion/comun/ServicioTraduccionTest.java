package com.bluedebug.gestion.comun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qué motor se coge y qué se hace con lo que devuelve.
 *
 * No se llama a ninguna API en ninguna de estas pruebas. Lo que se comprueba es
 * la regla que protege del único fallo grave que puede colarse: un modelo puede
 * devolver una línea de más o de menos, y emparejar por posición una lista
 * descuadrada pondría el titular equivocado en el idioma equivocado. Eso no lo ve
 * nadie hasta que lo lee alguien que hable ese idioma, que es justo lo que no se
 * puede permitir cuando el texto sale publicado en cuatro países.
 */
class ServicioTraduccionTest {

    private final ObjectMapper json = new ObjectMapper();

    private ServicioTraduccion servicio(String motor, String claveGemini, String claveClaude) {
        var propiedades = new PropiedadesTraduccion(motor, claveGemini, null, claveClaude, null);
        return new ServicioTraduccion(propiedades,
                new MotorGemini(propiedades, json),
                new MotorClaude(propiedades));
    }

    @Test
    @DisplayName("sin ninguna clave no hay traductor, y se dice por qué")
    void sinClaves() {
        var servicio = servicio("auto", null, null);

        assertFalse(servicio.configurado());

        var resultado = servicio.traducir(List.of("Una novedad"), "contexto", 90);
        assertFalse(resultado.correcto());
        assertTrue(resultado.mensaje().contains("GEMINI_API_KEY"), resultado.mensaje());
    }

    @Test
    @DisplayName("con las dos claves gana Gemini, que es el gratis")
    void automaticoPrefiereElGratis() {
        assertEquals("Gemini", servicio("auto", "clave-gemini", "clave-claude").motor());
    }

    @Test
    @DisplayName("con solo la de Anthropic se usa Claude")
    void soloClaude() {
        assertEquals("Claude", servicio("auto", null, "clave-claude").motor());
    }

    @Test
    @DisplayName("se puede forzar el motor por configuración")
    void motorForzado() {
        assertEquals("Claude", servicio("claude", "clave-gemini", "clave-claude").motor());
        assertEquals("Gemini", servicio("gemini", "clave-gemini", "clave-claude").motor());
    }

    @Test
    @DisplayName("un motor forzado sin clave cae al otro en vez de quedarse sin traducir")
    void motorForzadoSinClave() {
        // Una variable mal escrita no puede costar una versión publicada solo en
        // castellano habiendo un motor configurado y listo.
        assertEquals("Gemini", servicio("claude", "clave-gemini", null).motor());
    }

    @Test
    @DisplayName("las tres lenguas con el número de líneas correcto entran enteras")
    void todoCuadra() {
        var resultado = servicio("auto", "clave", null).recoger(new MotorTraduccion.Traduccion(
                List.of("One", "Two"), List.of("Un", "Deux"), List.of("Um", "Dois")), 2);

        assertTrue(resultado.correcto());
        assertEquals(3, resultado.porIdioma().size());
        assertEquals(List.of("Un", "Deux"), resultado.porIdioma().get("fr"));
    }

    @Test
    @DisplayName("la lengua que viene descuadrada se descarta y las demás se quedan")
    void unaDescuadrada() {
        var resultado = servicio("auto", "clave", null).recoger(new MotorTraduccion.Traduccion(
                List.of("One", "Two"), List.of("Un"), List.of("Um", "Dois")), 2);

        assertTrue(resultado.correcto());
        assertEquals(2, resultado.porIdioma().size());
        assertFalse(resultado.porIdioma().containsKey("fr"));
        assertTrue(resultado.mensaje().contains("fr"), resultado.mensaje());
    }

    @Test
    @DisplayName("una lengua con una línea vacía tampoco vale")
    void lineaVacia() {
        var resultado = servicio("auto", "clave", null).recoger(new MotorTraduccion.Traduccion(
                List.of("One", "  "), List.of("Un", "Deux"), List.of("Um", "Dois")), 2);

        assertFalse(resultado.porIdioma().containsKey("en"));
        assertEquals(2, resultado.porIdioma().size());
    }

    @Test
    @DisplayName("si no cuadra ninguna, es un fallo y no un éxito vacío")
    void ningunaCuadra() {
        var resultado = servicio("auto", "clave", null).recoger(new MotorTraduccion.Traduccion(
                List.of("One"), null, List.of()), 2);

        assertFalse(resultado.correcto());
        assertTrue(resultado.porIdioma().isEmpty());
    }
}
