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
        return new Builder(mensaje);
    }

    public static final class Builder {
        private final String mensaje;
        private final Map<String, Object> detalles = new LinkedHashMap<>();

        private Builder(String mensaje) {
            this.mensaje = mensaje;
        }

        public Builder con(String clave, Object valor) {
            detalles.put(clave, valor);
            return this;
        }

        public ResultadoAccion listo() {
            return new ResultadoAccion(true, mensaje, Map.copyOf(detalles));
        }
    }
}
