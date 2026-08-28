package com.bluedebug.gestion.conectores.modelo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lo que devuelve una acción cuando termina.
 *
 * Lleva {@code detalles} a propósito en vez de un simple mensaje: al mandar una
 * notificación importa mucho más «llegó a 84 de 91 dispositivos, 7 tokens
 * caducados» que un «hecho» a secas, y esa forma de contar es distinta en cada
 * acción.
 */
public record ResultadoAccion(boolean correcto, String mensaje, Map<String, Object> detalles) {

    public static ResultadoAccion ok(String mensaje) {
        return new ResultadoAccion(true, mensaje, Map.of());
    }

    public static ResultadoAccion error(String mensaje) {
        return new ResultadoAccion(false, mensaje, Map.of());
    }

    /** Constructor cómodo para ir apilando cifras sin montar el mapa a mano. */
    public static Builder correcta(String mensaje) {
        return new Builder(mensaje, true);
    }

    /**
     * Como {@link #correcta}, pero el resultado se marca como fallido.
     *
     * Existe para el caso que de otro modo se cuenta mal: una acción que se
     * ejecuta entera y sin errores, pero que no consigue nada —un aviso que sale
     * hacia nueve móviles y no llega a ninguno—. Eso no es un éxito, y pintarlo
     * en verde con las cifras malas debajo es la forma más rápida de que nadie se
     * fíe del panel. Lleva detalles igual, porque son justo los que explican por
     * qué no salió.
     */
    public static Builder fallida(String mensaje) {
        return new Builder(mensaje, false);
    }

    public static final class Builder {
        private final String mensaje;
        private final boolean correcto;
        private final Map<String, Object> detalles = new LinkedHashMap<>();

        private Builder(String mensaje, boolean correcto) {
            this.mensaje = mensaje;
            this.correcto = correcto;
        }

        public Builder con(String clave, Object valor) {
            detalles.put(clave, valor);
            return this;
        }

        public ResultadoAccion listo() {
            return new ResultadoAccion(correcto, mensaje, Map.copyOf(detalles));
        }
    }
}
