package com.bluedebug.gestion.comun;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Traduce con Claude, que es el motor de pago.
 *
 * Está por detrás de {@link MotorGemini} en la elección automática por una razón
 * y solo una: el otro es gratis y para seis titulares hace el mismo papel. Se usa
 * cuando no hay clave de Gemini, o cuando se pide a mano con
 * {@code BLUEDEBUG_TRADUCCION_MOTOR=claude}.
 *
 * Va por el SDK oficial, que trae la salida estructurada de serie: se le pasa la
 * clase y devuelve el objeto ya montado, sin JSON a mano en medio.
 */
@Component
public class MotorClaude implements MotorTraduccion {

    /**
     * Corto a propósito. Traducir seis titulares no lleva ni cinco segundos, y
     * quien está mirando el formulario no va a esperar diez.
     */
    private static final Duration ESPERA = Duration.ofSeconds(45);

    private final PropiedadesTraduccion propiedades;
    private final AnthropicClient cliente;

    public MotorClaude(PropiedadesTraduccion propiedades) {
        this.propiedades = propiedades;
        this.cliente = propiedades.hayClaude()
                ? AnthropicOkHttpClient.builder()
                        .apiKey(propiedades.clave())
                        .timeout(ESPERA)
                        .build()
                : null;
    }

    @Override
    public boolean configurado() {
        return cliente != null;
    }

    @Override
    public String nombre() {
        return "Claude";
    }

    @Override
    public Traduccion traducir(String sistema, String peticion) {
        StructuredMessageCreateParams<Traduccion> params = MessageCreateParams.builder()
                .model(propiedades.modeloElegido())
                .maxTokens(16000L)
                .system(sistema)
                .outputConfig(Traduccion.class)
                .addUserMessage(peticion)
                .build();

        var respuesta = cliente.messages().create(params);

        // Una negativa del modelo llega con 200 y sin contenido útil. Es rarísimo
        // traduciendo titulares de voleibol, pero si pasa hay que decirlo en vez
        // de publicar tres idiomas vacíos sin explicación.
        if (respuesta.stopReason().map(razon -> razon.toString().contains("refusal")).orElse(false)) {
            throw new IllegalStateException("Claude no quiso traducir este texto");
        }

        return respuesta.content().stream()
                .flatMap(bloque -> bloque.text().stream())
                .map(texto -> texto.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude contestó sin texto"));
    }
}
