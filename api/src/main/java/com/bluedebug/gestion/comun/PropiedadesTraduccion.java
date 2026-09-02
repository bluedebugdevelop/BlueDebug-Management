package com.bluedebug.gestion.comun;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Con qué se traduce.
 *
 * Hay dos motores y ninguno es obligatorio: sin ninguna clave el panel funciona
 * igual, y lo que se escriba se publica solo en castellano.
 *
 * @param motor        cuál usar: {@code auto} (el que haya, prefiriendo el
 *                     gratis), {@code gemini} o {@code claude}.
 * @param geminiClave  clave de la API de Gemini (AI Studio). Es la de la capa
 *                     gratuita.
 * @param geminiModelo el modelo de Gemini. Configurable porque estos nombres
 *                     cambian cada pocos meses y no vale la pena desplegar por eso.
 * @param clave        clave de API de Anthropic.
 * @param modelo       el modelo de Claude.
 */
@ConfigurationProperties(prefix = "bluedebug.traduccion")
public record PropiedadesTraduccion(
        String motor,
        String geminiClave,
        String geminiModelo,
        String clave,
        String modelo
) {

    private static final String MODELO_CLAUDE = "claude-opus-5";
    private static final String MODELO_GEMINI = "gemini-3.5-flash-lite";

    public boolean hayGemini() {
        return lleno(geminiClave);
    }

    public boolean hayClaude() {
        return lleno(clave);
    }

    /** Lo que se pidió por configuración, en minúsculas y sin sorpresas. */
    public String motorPedido() {
        return motor == null || motor.isBlank() ? "auto" : motor.trim().toLowerCase();
    }

    public String modeloElegido() {
        return lleno(modelo) ? modelo : MODELO_CLAUDE;
    }

    public String modeloGemini() {
        return lleno(geminiModelo) ? geminiModelo : MODELO_GEMINI;
    }

    private boolean lleno(String valor) {
        return valor != null && !valor.isBlank();
    }
}
