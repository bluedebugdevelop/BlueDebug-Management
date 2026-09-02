package com.bluedebug.gestion.comun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

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
 * QUÉ MOTOR SE USA
 *
 * Hay dos, {@link MotorGemini} (gratis) y {@link MotorClaude} (de pago). Con
 * {@code motor: auto} —lo normal— se coge el gratis si tiene clave y el otro si
 * no. Se puede forzar uno con {@code BLUEDEBUG_TRADUCCION_MOTOR}.
 *
 * REGLAS DE LA CASA
 *
 *   - Sin ninguna clave, {@link #configurado()} devuelve false y no se llama a
 *     nadie. El panel entero tiene que seguir funcionando sin esto configurado.
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

    private final MotorTraduccion motor;

    public ServicioTraduccion(PropiedadesTraduccion propiedades,
                              MotorGemini gemini,
                              MotorClaude claude) {
        this.motor = elegir(propiedades, gemini, claude);

        if (motor == null) {
            log.info("Traducción: sin claves (GEMINI_API_KEY o ANTHROPIC_API_KEY); "
                    + "lo que se publique irá solo en castellano");
        } else {
            log.info("Traducción: se usará {}", motor.nombre());
        }
    }

    /**
     * Con qué se traduce.
     *
     * El automático prefiere el gratis. Si se pide uno a mano y resulta no estar
     * configurado, se cae al otro en vez de quedarse sin traducir: una variable
     * mal escrita no debería costar una versión publicada solo en castellano.
     */
    private MotorTraduccion elegir(PropiedadesTraduccion propiedades,
                                   MotorGemini gemini,
                                   MotorClaude claude) {
        List<MotorTraduccion> orden = switch (propiedades.motorPedido()) {
            case "claude", "anthropic" -> List.of(claude, gemini);
            case "gemini", "google" -> List.of(gemini, claude);
            default -> List.of(gemini, claude);
        };
        return orden.stream().filter(MotorTraduccion::configurado).findFirst().orElse(null);
    }

    /**
     * Lo que devuelve una traducción.
     *
     * @param correcto  si se pudo traducir algo.
     * @param mensaje   qué contar en el formulario, salga bien o mal.
     * @param porIdioma las líneas traducidas, en el mismo orden que entraron.
     */
    public record Resultado(boolean correcto, String mensaje, Map<String, List<String>> porIdioma) {

        static Resultado fallo(String mensaje) {
            return new Resultado(false, mensaje, Map.of());
        }
    }

    public boolean configurado() {
        return motor != null;
    }

    /** Cómo se llama el motor que está puesto, o vacío si no hay ninguno. */
    public String motor() {
        return motor == null ? "" : motor.nombre();
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
            return Resultado.fallo("El panel no tiene traductor configurado "
                    + "(falta GEMINI_API_KEY o ANTHROPIC_API_KEY).");
        }
        if (lineas == null || lineas.isEmpty()) {
            return Resultado.fallo("No hay nada que traducir todavía");
        }

        try {
            MotorTraduccion.Traduccion traduccion = motor.traducir(sistema(contexto, maximo), numeradas(lineas));
            if (traduccion == null) {
                return Resultado.fallo(motor.nombre() + " no devolvió nada; inténtalo otra vez.");
            }
            return recoger(traduccion, lineas.size());

        } catch (RuntimeException e) {
            // Aquí caben desde un 401 por clave mal copiada hasta un corte de red.
            // Ninguno es motivo para tumbar el formulario: se cuenta y se sigue.
            log.warn("Traducción: {} no pudo traducir ({})", motor.nombre(), e.toString());
            return Resultado.fallo("No se pudo traducir con " + motor.nombre() + ": " + e.getMessage());
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
    Resultado recoger(MotorTraduccion.Traduccion traduccion, int esperadas) {
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
