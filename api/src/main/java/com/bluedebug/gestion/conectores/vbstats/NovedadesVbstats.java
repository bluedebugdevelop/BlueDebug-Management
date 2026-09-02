package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * El «qué hay de nuevo» de VBStats: de lo que se escribe en el panel a lo que
 * guarda la base de datos.
 *
 * Está aparte del conector, y sin dependencias de Spring, por una razón: es la
 * única pieza de la acción que tiene reglas propias —cuántas líneas caben, qué
 * iconos existen, qué pasa con un idioma a medias— y así se puede probar sola,
 * sin base de datos ni Stripe ni Firebase detrás.
 *
 * LO QUE SE ESCRIBE AQUÍ LO LEE OTRO EDITOR. VBStats ya tiene el suyo dentro de
 * la app (panel admin › Novedades de la versión), y las dos cosas escriben en la
 * misma fila de `whats_new_releases`. Por eso el formato de salida no es
 * negociable —{@code [{"icon": ..., "titles": {"es": ...}}]}— y los iconos son
 * exactamente los que aquel ofrece: el backend de VBStats rechaza cualquier otro
 * en su propio guardado, y un icono que él no conozca sería un hueco en blanco
 * en el diálogo. Si allí se añade uno, hay que añadirlo aquí.
 *
 * El formato de entrada es UN SOLO textarea, el del castellano, con UNA NOVEDAD
 * POR LÍNEA y opcionalmente un icono delante separado por una barra:
 *
 *     mejora | Copia una posición a todas las demás
 *     Seguimiento de equipos en el inicio
 *
 * Se eligió eso y no algo con más ceremonia porque lo escribe una persona en un
 * textarea, deprisa, el día que publica una versión. Los otros tres idiomas no se
 * escriben: llegan aquí ya traducidos ({@link com.bluedebug.gestion.comun.ServicioTraduccion}),
 * y los que falten se sirven en castellano.
 */
final class NovedadesVbstats {

    /** Tope de novedades por versión: nadie lee más, y el diálogo de la app no da para más. */
    static final int MAXIMO_NOVEDADES = 8;

    /** Lo que cabe en una línea del diálogo sin partirse en tres renglones. */
    static final int MAXIMO_TITULAR = 90;

    /** Los idiomas de la app. El castellano es el único obligatorio: los demás caen a él. */
    static final List<String> IDIOMAS = List.of("es", "en", "fr", "pt");

    static final String ICONO_POR_DEFECTO = "star-four-points-outline";

    /**
     * Los iconos que puede pedir una novedad, con nombre en castellano.
     *
     * A la derecha va el nombre de MaterialCommunityIcons, que es lo que entiende
     * la app. Se traducen aquí, y no allí, para que quien escribe las novedades no
     * tenga que conocer un catálogo de iconos en inglés: escribe «mejora» y ya.
     *
     * La columna de la derecha es, ni más ni menos, la lista que acepta el backend
     * de VBStats (`ALLOWED_ICONS` en routes/whatsNew.js) y que ofrece su editor
     * (`WHATS_NEW_ICONS` en services/whatsNew.ts). No se añade nada por libre.
     */
    static final Map<String, String> ICONOS = Map.ofEntries(
            Map.entry("nuevo", "star-four-points-outline"),
            Map.entry("mejora", "tune-variant"),
            Map.entry("movil", "cellphone-play"),
            Map.entry("copiar", "content-copy"),
            Map.entry("evolucion", "chart-timeline-variant"),
            Map.entry("estadisticas", "chart-box-outline"),
            Map.entry("google", "google"),
            Map.entry("diseno", "palette-outline"),
            Map.entry("equipo", "account-group"),
            Map.entry("plan", "crown"),
            Map.entry("aviso", "bell-ring-outline"),
            Map.entry("seguridad", "shield-check"),
            Map.entry("rapidez", "speedometer"),
            Map.entry("arreglo", "bug-check-outline"));

    private NovedadesVbstats() {
    }

    /**
     * Lo que hay que guardar, ya listo.
     *
     * @param itemsJson  el array que va a la columna `items`.
     * @param novedades  cuántas líneas tiene.
     * @param idiomas    en cuántos idiomas se escribió, contando el castellano.
     */
    record Publicacion(String itemsJson, int novedades, int idiomas) {

        /** Los idiomas que se van a leer en castellano por no haberlos escrito. */
        int sinTraducir() {
            return IDIOMAS.size() - idiomas;
        }
    }

    /** Los nombres de icono que se pueden escribir, para ponerlos en la ayuda del campo. */
    static String iconosDisponibles() {
        return String.join(", ", new TreeSet<>(ICONOS.keySet()));
    }

    /**
     * Convierte lo escrito en el formulario en el JSON que guarda la base de datos.
     *
     * @param castellano   lo tecleado, una novedad por línea.
     * @param traducciones lo que devolvió el traductor, por idioma y en el mismo
     *                     orden. Puede venir vacío: entonces se publica solo en
     *                     castellano y la app lo enseña en castellano a todos.
     */
    static Publicacion preparar(ObjectMapper json, String castellano, Map<String, List<String>> traducciones) {
        List<Novedad> base = leerLineas(castellano, "es");
        if (base.isEmpty()) {
            throw new PeticionInvalida("Hay que escribir las novedades en castellano");
        }

        // Una traducción con más o menos líneas que el original no se puede casar
        // con nada, así que se tira esa lengua entera y se publica sin ella: en la
        // app se leerá el castellano, que es feo pero cierto. Emparejar por
        // posición una lista descuadrada pondría el titular equivocado en el
        // idioma equivocado, y eso no lo ve nadie hasta que lo lee un francés.
        Map<String, List<String>> buenas = new LinkedHashMap<>();
        traducciones.forEach((idioma, lineas) -> {
            if (lineas != null && lineas.size() == base.size()) {
                buenas.put(idioma, lineas);
            }
        });

        ArrayNode items = json.createArrayNode();
        for (int i = 0; i < base.size(); i++) {
            ObjectNode item = items.addObject();
            // El icono es el de la línea en castellano: es el mismo dibujo en los
            // cuatro idiomas, y pedirlo cuatro veces solo daría ocasión de que no
            // coincidieran.
            item.put("icon", base.get(i).icono());
            // `titles`, no `text`: es la clave que leen la app y su propio editor.
            ObjectNode textos = item.putObject("titles");
            textos.put("es", base.get(i).titular());
            for (var entrada : buenas.entrySet()) {
                textos.put(entrada.getKey(), entrada.getValue().get(i).strip());
            }
        }

        try {
            // +1 por el castellano, que no es una traducción pero sí un idioma.
            return new Publicacion(json.writeValueAsString(items), base.size(), buenas.size() + 1);
        } catch (JsonProcessingException e) {
            throw new PeticionInvalida("No se pudieron preparar las novedades: " + e.getMessage());
        }
    }

    /**
     * Solo los titulares, sin el icono de delante.
     *
     * Es lo que se manda a traducir: el icono es el mismo en los cuatro idiomas,
     * y mandarlo sería pedirle al traductor que devuelva intacto algo que no
     * tiene por qué entender.
     */
    static List<String> titulares(String bruto) {
        return leerLineas(bruto, "es").stream().map(Novedad::titular).toList();
    }

    /** Una línea del formulario ya partida en icono y titular. */
    private record Novedad(String icono, String titular) {
    }

    private static List<Novedad> leerLineas(String bruto, String idioma) {
        List<Novedad> novedades = new ArrayList<>();
        if (bruto == null || bruto.isBlank()) {
            return novedades;
        }

        for (String linea : bruto.split("\\R")) {
            String limpia = linea.strip();
            if (limpia.isEmpty()) {
                continue;
            }

            String icono = ICONO_POR_DEFECTO;
            String titular = limpia;

            int barra = limpia.indexOf('|');
            if (barra >= 0) {
                icono = icono(limpia.substring(0, barra).strip(), idioma);
                titular = limpia.substring(barra + 1).strip();
            }

            if (titular.isEmpty()) {
                throw new PeticionInvalida("Hay una línea en " + idioma + " con icono y sin texto");
            }
            if (titular.length() > MAXIMO_TITULAR) {
                throw new PeticionInvalida("«" + recortar(titular) + "» pasa de " + MAXIMO_TITULAR
                        + " caracteres; en el diálogo de la app no cabe");
            }

            novedades.add(new Novedad(icono, titular));
        }

        if (novedades.size() > MAXIMO_NOVEDADES) {
            throw new PeticionInvalida("Como mucho " + MAXIMO_NOVEDADES + " novedades por versión; en "
                    + idioma + " hay " + novedades.size());
        }
        return novedades;
    }

    private static String icono(String pedido, String idioma) {
        if (pedido.isEmpty()) {
            return ICONO_POR_DEFECTO;
        }

        String normalizado = pedido.toLowerCase(Locale.ROOT)
                .replace("ñ", "n")
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u");

        String conocido = ICONOS.get(normalizado);
        if (conocido != null) {
            return conocido;
        }
        // También vale el nombre de MaterialCommunityIcons tal cual, que es como se
        // llaman en el editor de dentro de la app: lo escrito allí se puede copiar
        // aquí sin traducir nada. Pero solo los de la lista: cualquier otro lo
        // rechazaría el backend de VBStats en su propio guardado, y por aquí se
        // colaría hasta el diálogo como un hueco en blanco.
        if (ICONOS.containsValue(normalizado)) {
            return normalizado;
        }
        throw new PeticionInvalida("«" + pedido + "» (en " + idioma + ") no es un icono. "
                + "Usa uno de: " + iconosDisponibles());
    }

    private static String recortar(String texto) {
        return texto.length() <= 40 ? texto : texto.substring(0, 40) + "…";
    }
}
