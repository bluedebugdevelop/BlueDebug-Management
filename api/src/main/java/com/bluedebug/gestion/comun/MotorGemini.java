package com.bluedebug.gestion.comun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduce con Gemini, que es el motor gratis.
 *
 * Google da una capa gratuita en la API de Gemini, y traducir seis titulares al
 * publicar una versión cabe de sobra dentro de ella. Por eso es el motor que se
 * elige solo cuando hay clave: entre dos que hacen el mismo trabajo, se coge el
 * que no pasa factura.
 *
 * VA POR HTTP Y NO POR SDK, A PROPÓSITO
 *
 * La llamada es una sola petición con una sola forma, y el SDK de Google arrastra
 * media pila de bibliotecas de Google que este panel no necesita para nada más.
 * Con {@link RestClient} —el mismo que ya usa el push del club— son treinta
 * líneas y ninguna dependencia nueva.
 *
 * LEER LA RESPUESTA CON CUIDADO
 *
 * En los SDK el texto se saca de {@code output_text}, que es una comodidad del
 * cliente; por REST llega dentro de {@code outputs[]}, una lista de bloques.
 * Aquí se miran los dos, y también {@code steps[]}, porque esa forma ha cambiado
 * ya una vez y el síntoma de que vuelva a cambiar tiene que ser «no se pudo
 * traducir» y no una excepción rara a mitad de una publicación.
 */
@Component
public class MotorGemini implements MotorTraduccion {

    private static final Logger log = LoggerFactory.getLogger(MotorGemini.class);

    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/interactions";

    /**
     * El esquema que se le exige a la respuesta: tres listas de cadenas y nada
     * más. Sin esto, un modelo devuelve tan pronto un JSON como un JSON dentro de
     * un bloque de markdown con una frase de cortesía delante.
     */
    private static final Map<String, Object> ESQUEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "en", listaDeCadenas(),
                    "fr", listaDeCadenas(),
                    "pt", listaDeCadenas()),
            "required", List.of("en", "fr", "pt"));

    private final PropiedadesTraduccion propiedades;
    private final ObjectMapper json;
    private final RestClient http;

    public MotorGemini(PropiedadesTraduccion propiedades, ObjectMapper json) {
        this.propiedades = propiedades;
        this.json = json;
        this.http = RestClient.builder().baseUrl(URL).build();
    }

    private static Map<String, Object> listaDeCadenas() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    @Override
    public boolean configurado() {
        return propiedades.hayGemini();
    }

    @Override
    public String nombre() {
        return "Gemini";
    }

    @Override
    public Traduccion traducir(String sistema, String peticion) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("model", propiedades.modeloGemini());
        cuerpo.put("system_instruction", sistema);
        cuerpo.put("input", peticion);
        cuerpo.put("response_format", Map.of(
                "type", "text",
                "mime_type", "application/json",
                "schema", ESQUEMA));

        JsonNode respuesta = http.post()
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", propiedades.geminiClave())
                .body(cuerpo)
                .retrieve()
                .body(JsonNode.class);

        String texto = textoDe(respuesta);
        if (texto == null || texto.isBlank()) {
            throw new IllegalStateException("Gemini contestó sin texto");
        }

        try {
            return json.readValue(texto, Traduccion.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Gemini: la respuesta no era el JSON pedido: {}", recorte(texto));
            throw new IllegalStateException("Gemini no devolvió el formato pedido");
        }
    }

    /** Busca el texto donde la API lo ha ido poniendo en sus distintas versiones. */
    private String textoDe(JsonNode respuesta) {
        if (respuesta == null) {
            return null;
        }
        if (respuesta.hasNonNull("output_text")) {
            return respuesta.get("output_text").asText();
        }

        List<String> trozos = new ArrayList<>();
        recogerTexto(respuesta.path("outputs"), trozos);
        if (trozos.isEmpty()) {
            // La forma larga: el turno completo, con sus pasos.
            for (JsonNode paso : respuesta.path("steps")) {
                recogerTexto(paso.path("outputs"), trozos);
                recogerTexto(paso.path("content"), trozos);
            }
        }
        return trozos.isEmpty() ? null : String.join("", trozos);
    }

    private void recogerTexto(JsonNode bloques, List<String> destino) {
        if (bloques == null || !bloques.isArray()) {
            return;
        }
        for (JsonNode bloque : bloques) {
            if (bloque.hasNonNull("text")) {
                destino.add(bloque.get("text").asText());
            }
        }
    }

    private String recorte(String texto) {
        return texto.length() <= 200 ? texto : texto.substring(0, 200) + "…";
    }
}
