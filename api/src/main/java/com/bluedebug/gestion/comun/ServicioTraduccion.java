package com.bluedebug.gestion.comun;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduce al inglés, el francés y el portugués lo que se escribe en castellano.
 *
 * Las apps de BlueDebug hablan cuatro idiomas y quien escribe en el panel, uno.
 * Hasta ahora eso significaba o pegar cuatro veces lo mismo o publicar solo en
 * castellano; esto último funciona —el servidor cae al castellano cuando falta
 * una traducción— y por eso es tan fácil que se quede así para siempre.
 *
 * POR QUÉ UN MODELO Y NO UN TRADUCTOR
 *
 * Lo que se traduce aquí son titulares de interfaz sueltos: cuatro palabras sin
 * frase alrededor. Es justo donde un traductor automático elige mal la acepción
 * («posición» de un jugador, «set» de un partido) porque no tiene de dónde
 * deducirla. Al modelo se le puede contar qué es la app y para qué sirve el
 * texto, y eso es lo que arregla esos casos.
 *
 * REGLAS DE LA CASA
 *
 *   - Sin clave, {@link #configurado()} devuelve false y no se llama a nadie. El
 *     panel entero tiene que seguir funcionando sin esto configurado.
 *   - Nunca lanza por un fallo de red o de la API: devuelve el motivo en un
 *     {@link Resultado} para poder enseñarlo en el formulario.
 *   - El número de líneas que entra es el que sale. Si el modelo devuelve otra
 *     cosa, se descarta esa lengua entera antes que emparejar titulares
 *     traducidos con los que no les tocan.
 */
@Component
@EnableConfigurationProperties(PropiedadesTraduccion.class)
public class ServicioTraduccion {

    private static final Logger log = LoggerFactory.getLogger(ServicioTraduccion.class);

    /** Los idiomas a los que se traduce, en el orden en que se enseñan. */
    public static final List<String> IDIOMAS = List.of("en", "fr", "pt");

    /**
     * Corto a propósito. Traducir seis titulares no lleva ni cinco segundos, y
     * quien está mirando el formulario no va a esperar diez.
     */
    private static final Duration ESPERA = Duration.ofSeconds(45);

    private final PropiedadesTraduccion propiedades;
    private final AnthropicClient cliente;

    public ServicioTraduccion(PropiedadesTraduccion propiedades) {
        this.propiedades = propiedades;
        this.cliente = propiedades.hayClave()
                ? AnthropicOkHttpClient.builder()
                        .apiKey(propiedades.clave())
                        .timeout(ESPERA)
                        .build()
                : null;

        if (cliente == null) {
            log.info("Traducción: sin ANTHROPIC_API_KEY; los idiomas se rellenan a mano");
        }
    }

    /**
     * Lo que devuelve una traducción.
     *
     * @param correcto si se pudo traducir algo.
     * @param mensaje  qué contar en el formulario, salga bien o mal.
     * @param porIdioma las líneas traducidas, en el mismo orden que entraron.
     */
    public record Resultado(boolean correcto, String mensaje, Map<String, List<String>> porIdioma) {

        static Resultado fallo(String mensaje) {
            return new Resultado(false, mensaje, Map.of());
        }
    }

    /** La respuesta del modelo, ya con forma: una lista por idioma. */
    public record Traduccion(List<String> en, List<String> fr, List<String> pt) {
    }

    public boolean configurado() {
        return cliente != null;
    }

    /**
     * Traduce las líneas manteniendo el orden y el número.
     *
     * @param lineas   los titulares en castellano.
     * @param contexto qué es esto y dónde se lee, para que el modelo elija bien
     *                 la acepción. Cuanto más concreto, mejor sale.
     * @param maximo   tope de caracteres por línea, el mismo que valida el
     *                 formulario: una traducción que no cabe no sirve de nada.
     */
    public Resultado traducir(List<String> lineas, String contexto, int maximo) {
        if (!configurado()) {
            return Resultado.fallo("Falta ANTHROPIC_API_KEY en el panel; escribe los idiomas a mano.");
        }
        if (lineas == null || lineas.isEmpty()) {
            return Resultado.fallo("No hay nada que traducir todavía");
        }

        try {
            StructuredMessageCreateParams<Traduccion> params = MessageCreateParams.builder()
                    .model(propiedades.modeloElegido())
                    .maxTokens(16000L)
                    .system(sistema(contexto, maximo))
                    .outputConfig(Traduccion.class)
                    .addUserMessage(numeradas(lineas))
                    .build();

            var respuesta = cliente.messages().create(params);

            // Una negativa del modelo llega con 200 y sin contenido útil. Es
            // rarísimo traduciendo titulares de voleibol, pero si pasa hay que
            // decirlo en vez de enseñar tres campos vacíos sin explicación.
            if (respuesta.stopReason().map(razon -> razon.toString().contains("refusal")).orElse(false)) {
                return Resultado.fallo("El modelo no quiso traducir este texto; escríbelo a mano.");
            }

            Traduccion traduccion = respuesta.content().stream()
                    .flatMap(bloque -> bloque.text().stream())
                    .map(texto -> texto.text())
                    .findFirst()
                    .orElse(null);

            if (traduccion == null) {
                return Resultado.fallo("El traductor no devolvió nada; inténtalo otra vez.");
            }

            return recoger(traduccion, lineas.size());

        } catch (RuntimeException e) {
            // Aquí caben desde un 401 por clave mal copiada hasta un corte de red.
            // Ninguno es motivo para tumbar el formulario: se cuenta y se sigue.
            log.warn("Traducción: no se pudo traducir ({})", e.toString());
            return Resultado.fallo("No se pudo traducir: " + e.getMessage());
        }
    }

    /**
     * Junta lo que vino con la forma correcta y descarta el resto.
     *
     * Un idioma con un número de líneas distinto al original se tira entero. Es
     * deliberado: emparejar por posición lo que ya no cuadra pondría el titular
     * equivocado en el idioma equivocado, y eso no se ve hasta que lo lee alguien
     * que hable ese idioma.
     */
    Resultado recoger(Traduccion traduccion, int esperadas) {
        Map<String, List<String>> porIdioma = new LinkedHashMap<>();
        List<String> descartados = new ArrayList<>();

        Map<String, List<String>> crudas = new LinkedHashMap<>();
        crudas.put("en", traduccion.en());
        crudas.put("fr", traduccion.fr());
        crudas.put("pt", traduccion.pt());

        crudas.forEach((idioma, lineas) -> {
            if (lineas != null && lineas.size() == esperadas && lineas.stream().noneMatch(this::vacia)) {
                porIdioma.put(idioma, lineas.stream().map(String::strip).toList());
            } else {
                descartados.add(idioma);
            }
        });

        if (porIdioma.isEmpty()) {
            return Resultado.fallo("La traducción vino descuadrada; inténtalo otra vez.");
        }
        if (!descartados.isEmpty()) {
            return new Resultado(true,
                    "Traducido, pero " + String.join(" y ", descartados)
                            + " vino descuadrado y se ha dejado sin rellenar.",
                    porIdioma);
        }
        return new Resultado(true, "Traducido al inglés, el francés y el portugués", porIdioma);
    }

    private boolean vacia(String linea) {
        return linea == null || linea.isBlank();
    }

    private String sistema(String contexto, int maximo) {
        return """
                Traduces textos de interfaz del castellano al inglés, el francés y el portugués de Portugal.

                Contexto: %s

                Reglas:
                - Devuelve EXACTAMENTE tantas líneas por idioma como recibas, en el mismo orden.
                - Son textos de interfaz, no prosa: mismo tono, misma brevedad, sin punto final \
                si el original no lo lleva.
                - Máximo %d caracteres por línea. Si no cabe una traducción literal, acorta.
                - Los nombres propios (VBStats, Google, Apple) no se traducen.
                - Traduce el sentido, no palabra por palabra: son términos de voleibol y de app móvil.
                """.formatted(contexto, maximo);
    }

    private String numeradas(List<String> lineas) {
        StringBuilder texto = new StringBuilder("Traduce estas ")
                .append(lineas.size())
                .append(lineas.size() == 1 ? " línea:" : " líneas:")
                .append("\n\n");
        for (int i = 0; i < lineas.size(); i++) {
            texto.append(i + 1).append(". ").append(lineas.get(i)).append('\n');
        }
        return texto.toString();
    }
}
