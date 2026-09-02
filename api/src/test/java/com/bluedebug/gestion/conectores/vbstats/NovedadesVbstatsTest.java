package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private Map<String, String> escrito(String es, String en, String fr, String pt) {
        Map<String, String> texto = new LinkedHashMap<>();
        texto.put("es", es);
        texto.put("en", en);
        texto.put("fr", fr);
        texto.put("pt", pt);
        return texto;
    }

    @Test
    @DisplayName("una línea por novedad, con el icono traducido a su nombre real")
    void lineasEIconos() {
        var publicacion = NovedadesVbstats.preparar(json, escrito("""
                mejora | Copia una posición a todas las demás
                Seguimiento de equipos en el inicio
                """, "", "", ""));

        assertEquals(2, publicacion.novedades());
        assertEquals(1, publicacion.idiomas());
        assertEquals(3, publicacion.sinTraducir());
        assertTrue(publicacion.itemsJson().contains("\"icon\":\"tune-variant\""), publicacion.itemsJson());
        // Sin icono declarado se usa el de por defecto, no se queda vacío.
        assertTrue(publicacion.itemsJson().contains("\"icon\":\"star-four-points-outline\""),
                publicacion.itemsJson());
    }

    @Test
    @DisplayName("cada idioma escrito viaja en su propia clave")
    void variosIdiomas() {
        var publicacion = NovedadesVbstats.preparar(json,
                escrito("Novedad", "News", "", "Novidade"));

        assertEquals(3, publicacion.idiomas());
        assertTrue(publicacion.itemsJson().contains("\"titles\""), publicacion.itemsJson());
        assertEquals(1, publicacion.sinTraducir());
        assertTrue(publicacion.itemsJson().contains("\"es\":\"Novedad\""), publicacion.itemsJson());
        assertTrue(publicacion.itemsJson().contains("\"en\":\"News\""), publicacion.itemsJson());
        assertTrue(publicacion.itemsJson().contains("\"pt\":\"Novidade\""), publicacion.itemsJson());
        // El francés no se guarda vacío: al servirlo cae al castellano.
        assertTrue(!publicacion.itemsJson().contains("\"fr\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("una traducción descuadrada se rechaza antes de guardarla")
    void traduccionDescuadrada() {
        var fallo = assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json,
                escrito("Una\nDos", "Only one", "", "")));

        assertTrue(fallo.getMessage().contains("en"), fallo.getMessage());
    }

    @Test
    @DisplayName("sin castellano no se publica nada")
    void castellanoObligatorio() {
        assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json,
                escrito("", "Only english", "", "")));
    }

    @Test
    @DisplayName("las líneas en blanco no cuentan como novedad")
    void lineasEnBlanco() {
        var publicacion = NovedadesVbstats.preparar(json, escrito("""

                Una novedad

                Otra novedad
                """, "", "", ""));

        assertEquals(2, publicacion.novedades());
    }

    @Test
    @DisplayName("un titular que no cabe en el diálogo se rechaza")
    void titularDemasiadoLargo() {
        String largo = "x".repeat(NovedadesVbstats.MAXIMO_TITULAR + 1);
        assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json, escrito(largo, "", "", "")));
    }

    @Test
    @DisplayName("no se admiten más novedades de las que caben")
    void demasiadasNovedades() {
        String muchas = "Novedad\n".repeat(NovedadesVbstats.MAXIMO_NOVEDADES + 1);
        assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json, escrito(muchas, "", "", "")));
    }

    @Test
    @DisplayName("el nombre de MaterialCommunityIcons vale tal cual si es de la lista")
    void iconoDeMaterialCommunityIcons() {
        var publicacion = NovedadesVbstats.preparar(json,
                escrito("bell-ring-outline | Avisos nuevos", "", "", ""));

        assertTrue(publicacion.itemsJson().contains("\"icon\":\"bell-ring-outline\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("un icono que la app no conoce se rechaza, aunque parezca uno")
    void iconoFueraDeLaLista() {
        // El backend de VBStats rechaza los que no están en ALLOWED_ICONS, y en el
        // diálogo se vería un hueco en blanco: mejor pararlo aquí.
        assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json,
                escrito("volleyball-outline | Marcador nuevo", "", "", "")));
    }

    @Test
    @DisplayName("un icono con espacios o símbolos se rechaza con la lista de los válidos")
    void iconoImposible() {
        var fallo = assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json,
                escrito("un icono raro | Novedad", "", "", "")));

        assertTrue(fallo.getMessage().contains("mejora"), fallo.getMessage());
    }

    @Test
    @DisplayName("un icono con acento se reconoce igual")
    void iconoConAcento() {
        var publicacion = NovedadesVbstats.preparar(json, escrito("Diseño | Pantallas nuevas", "", "", ""));

        assertTrue(publicacion.itemsJson().contains("\"icon\":\"palette-outline\""), publicacion.itemsJson());
    }

    @Test
    @DisplayName("una línea con icono y sin texto se rechaza")
    void iconoSinTexto() {
        assertThrows(PeticionInvalida.class, () -> NovedadesVbstats.preparar(json, escrito("mejora |", "", "", "")));
    }
}
