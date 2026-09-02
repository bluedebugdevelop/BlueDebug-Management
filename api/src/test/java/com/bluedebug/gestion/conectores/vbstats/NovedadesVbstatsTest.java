package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lo que se teclea en el panel, convertido en lo que guarda la base de datos.
 *
 * Se prueba aquí y no contra la acción entera porque estas reglas son las únicas
 * que pueden dejar en producción una lista de novedades mal formada, y hacerlo a
 * mano significaría publicar de verdad para verlo.
 */
class NovedadesVbstatsTest {

    private final ObjectMapper json = new ObjectMapper();

    /** Lo normal: se escribe en castellano y el traductor pone el resto. */
    private static final Map<String, List<String>> SIN_TRADUCIR = Map.of();

    @Test
    @DisplayName("una línea por novedad, con el icono traducido a su nombre real")
    void lineasEIconos() {
        var publicacion = NovedadesVbstats.preparar(json, """
                mejora | Copia una posición a todas las demás
                Seguimiento de equipos en el inicio
                """, SIN_TRADUCIR);

        assertEquals(2, publicacion.novedades());
        assertTrue(publicacion.itemsJson().contains("\"icon\":\"tune-variant\""), publicacion.itemsJson());
        // Sin icono declarado se usa el de por defecto, no se queda vacío.
        assertTrue(publicacion.itemsJson().contains("\"icon\":\"star-four-points-outline\""),
                publicacion.itemsJson());
    }

    @Test
    @DisplayName("sin traducción se publica solo en castellano, y se sabe cuántos faltan")
    void soloCastellano() {
        var publicacion = NovedadesVbstats.preparar(json, "Una novedad", SIN_TRADUCIR);

        assertEquals(1, publicacion.idiomas());
        assertEquals(3, publicacion.sinTraducir());
        assertTrue(publicacion.itemsJson().contains("\"es\":\"Una novedad\""), publicacion.itemsJson());
        assertFalse(publicacion.itemsJson().contains("\"en\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("cada idioma traducido viaja en su propia clave, dentro de titles")
    void conTraducciones() {
        var publicacion = NovedadesVbstats.preparar(json, "Novedad",
                Map.of("en", List.of("News"), "fr", List.of("Nouveauté"), "pt", List.of("Novidade")));

        assertEquals(4, publicacion.idiomas());
        assertEquals(0, publicacion.sinTraducir());
        assertTrue(publicacion.itemsJson().contains("\"titles\""), publicacion.itemsJson());
        assertTrue(publicacion.itemsJson().contains("\"en\":\"News\""), publicacion.itemsJson());
        assertTrue(publicacion.itemsJson().contains("\"pt\":\"Novidade\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("una traducción descuadrada se cae sola, sin tumbar la publicación")
    void traduccionDescuadrada() {
        // El servicio de traducción ya descarta las que no cuadran; esto es el
        // cinturón por si algún día entra por otro sitio. Publicar en castellano
        // es peor que publicar en cuatro idiomas, pero es infinitamente mejor que
        // publicar el titular equivocado en francés.
        var publicacion = NovedadesVbstats.preparar(json, "Una\nDos",
                Map.of("en", List.of("Only one"), "fr", List.of("Un", "Deux")));

        // Quedan el castellano y el francés: el inglés se cayó entero.
        assertEquals(2, publicacion.idiomas());
        assertFalse(publicacion.itemsJson().contains("Only one"), publicacion.itemsJson());
        assertTrue(publicacion.itemsJson().contains("\"fr\":\"Un\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("sin castellano no se publica nada")
    void castellanoObligatorio() {
        assertThrows(PeticionInvalida.class,
                () -> NovedadesVbstats.preparar(json, "   ", SIN_TRADUCIR));
    }

    @Test
    @DisplayName("las líneas en blanco no cuentan como novedad")
    void lineasEnBlanco() {
        var publicacion = NovedadesVbstats.preparar(json, """

                Una novedad

                Otra novedad
                """, SIN_TRADUCIR);

        assertEquals(2, publicacion.novedades());
    }

    @Test
    @DisplayName("un titular que no cabe en el diálogo se rechaza")
    void titularDemasiadoLargo() {
        String largo = "x".repeat(NovedadesVbstats.MAXIMO_TITULAR + 1);
        assertThrows(PeticionInvalida.class,
                () -> NovedadesVbstats.preparar(json, largo, SIN_TRADUCIR));
    }

    @Test
    @DisplayName("no se admiten más novedades de las que caben")
    void demasiadasNovedades() {
        String muchas = "Novedad\n".repeat(NovedadesVbstats.MAXIMO_NOVEDADES + 1);
        assertThrows(PeticionInvalida.class,
                () -> NovedadesVbstats.preparar(json, muchas, SIN_TRADUCIR));
    }

    @Test
    @DisplayName("el nombre de MaterialCommunityIcons vale tal cual si es de la lista")
    void iconoDeMaterialCommunityIcons() {
        var publicacion = NovedadesVbstats.preparar(json,
                "bell-ring-outline | Avisos nuevos", SIN_TRADUCIR);

        assertTrue(publicacion.itemsJson().contains("\"icon\":\"bell-ring-outline\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("un icono que la app no conoce se rechaza, aunque parezca uno")
    void iconoFueraDeLaLista() {
        // El backend de VBStats rechaza los que no están en ALLOWED_ICONS, y en el
        // diálogo se vería un hueco en blanco: mejor pararlo aquí.
        assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json,
                "volleyball-outline | Marcador nuevo", SIN_TRADUCIR));
    }

    @Test
    @DisplayName("un icono con espacios o símbolos se rechaza con la lista de los válidos")
    void iconoImposible() {
        var fallo = assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json,
                "un icono raro | Novedad", SIN_TRADUCIR));

        assertTrue(fallo.getMessage().contains("mejora"), fallo.getMessage());
    }

    @Test
    @DisplayName("un icono con acento se reconoce igual")
    void iconoConAcento() {
        var publicacion = NovedadesVbstats.preparar(json, "Diseño | Pantallas nuevas", SIN_TRADUCIR);

        assertTrue(publicacion.itemsJson().contains("\"icon\":\"palette-outline\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("una línea con icono y sin texto se rechaza")
    void iconoSinTexto() {
        assertThrows(PeticionInvalida.class,
                () -> NovedadesVbstats.preparar(json, "mejora |", SIN_TRADUCIR));
    }

    @Test
    @DisplayName("lo que se manda a traducir va sin el icono delante")
    void titularesSinIcono() {
        var titulares = NovedadesVbstats.titulares("""
                mejora | Copia una posición a todas las demás
                Seguimiento de equipos en el inicio
                """);

        assertEquals(2, titulares.size());
        assertEquals("Copia una posición a todas las demás", titulares.get(0));
        assertEquals("Seguimiento de equipos en el inicio", titulares.get(1));
    }
}
