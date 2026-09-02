package com.bluedebug.gestion.comun;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La cuenta con la que el panel traduce.
 *
 * @param clave  clave de API de Anthropic. Si falta, el panel funciona igual y
 *               los campos de los otros idiomas se rellenan a mano: traducir es
 *               una comodidad, no un requisito para administrar nada.
 * @param modelo el modelo al que se le pide. Se deja configurable para poder
 *               cambiarlo sin tocar código el día que salga uno mejor.
 */
@ConfigurationProperties(prefix = "bluedebug.traduccion")
public record PropiedadesTraduccion(String clave, String modelo) {

    private static final String MODELO_POR_DEFECTO = "claude-opus-5";

    public boolean hayClave() {
        return clave != null && !clave.isBlank();
    }

    public String modeloElegido() {
        return modelo == null || modelo.isBlank() ? MODELO_POR_DEFECTO : modelo;
    }
}
