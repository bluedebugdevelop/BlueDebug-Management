package com.bluedebug.gestion.seguridad;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * La configuración de quién entra y cómo.
 *
 * @param googleClientId  el client id de OAuth del proyecto de Google. No es un
 *                        secreto: va en el HTML del botón de acceso.
 * @param secreto         la clave con la que se firma la cookie de sesión. Esto SÍ
 *                        es un secreto, y de los gordos: con él se fabrican sesiones
 *                        válidas de administrador.
 * @param permitidos      lista blanca de correos. Nadie más entra, tenga la cuenta
 *                        de Google que tenga.
 * @param horasSesion     cuánto dura la sesión antes de tener que volver a entrar.
 * @param cookieSegura    marca la cookie como Secure. En producción, siempre; en
 *                        local con http, hay que apagarlo o el navegador la tira.
 * @param origenesWeb     de dónde se acepta al front en desarrollo (el ng serve).
 */
@ConfigurationProperties(prefix = "bluedebug.seguridad")
public record PropiedadesSeguridad(
        String googleClientId,
        String secreto,
        List<String> permitidos,
        int horasSesion,
        boolean cookieSegura,
        List<String> origenesWeb
) {
    /**
     * Los correos se comparan siempre en minúsculas y sin espacios. Google
     * devuelve el correo tal y como lo escribió su dueño al registrarse, y una
     * mayúscula despistada en la variable de entorno dejaría fuera al único
     * administrador que hay.
     */
    public boolean permitido(String email) {
        if (email == null || permitidos == null) {
            return false;
        }
        String limpio = email.trim().toLowerCase(Locale.ROOT);
        return permitidos.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .anyMatch(limpio::equals);
    }

    public boolean googleConfigurado() {
        return googleClientId != null && !googleClientId.isBlank();
    }
}
