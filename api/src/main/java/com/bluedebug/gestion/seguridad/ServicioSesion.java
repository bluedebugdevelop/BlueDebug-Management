package com.bluedebug.gestion.seguridad;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * La sesión del panel: un JWT firmado que viaja en una cookie HttpOnly.
 *
 * Por qué cookie y no cabecera Authorization: el token de sesión de un panel de
 * administración es la llave de todo, y una cookie HttpOnly es el único sitio
 * del navegador al que el JavaScript de la página NO llega. Si algún día se cuela
 * un script raro —una dependencia comprometida, un pegado tonto en la consola—
 * con la llave en localStorage se la lleva; con HttpOnly, no.
 *
 * Por qué JWT y no sesión en memoria: no hay estado que replicar, así que el
 * contenedor se puede reiniciar o duplicar sin echar a nadie fuera. El precio es
 * que un token no se puede revocar antes de que caduque; con una lista blanca de
 * un puñado de correos y sesiones de horas, es un precio barato.
 */
@Service
public class ServicioSesion {

    private static final Logger log = LoggerFactory.getLogger(ServicioSesion.class);

    /** El nombre de la cookie. Con prefijo propio para no chocar con nada. */
    public static final String COOKIE = "bdm_sesion";

    private final PropiedadesSeguridad propiedades;
    private SecretKey clave;

    public ServicioSesion(PropiedadesSeguridad propiedades) {
        this.propiedades = propiedades;
    }

    @PostConstruct
    void prepararClave() {
        String secreto = propiedades.secreto();

        // HS256 necesita 256 bits. Un secreto más corto no es «menos seguro»: jjwt
        // se niega a firmar y la aplicación no arrancaría, así que se avisa aquí con
        // un mensaje que se entiende.
        if (secreto == null || secreto.getBytes(StandardCharsets.UTF_8).length < 32) {
            byte[] aleatorio = new byte[32];
            new SecureRandom().nextBytes(aleatorio);
            this.clave = Keys.hmacShaKeyFor(aleatorio);
            log.warn("""
                    BLUEDEBUG_SECRETO no está puesto o tiene menos de 32 caracteres.
                    Se ha generado uno al azar para esta ejecución: las sesiones se caerán
                    en cada reinicio. Para producción, genera uno con:
                      openssl rand -base64 48""");
        } else {
            this.clave = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Fabrica la cookie de sesión para un administrador ya verificado. */
    public ResponseCookie abrirSesion(Administrador admin) {
        Duration duracion = Duration.ofHours(Math.max(1, propiedades.horasSesion()));
        Instant ahora = Instant.now();

        String token = Jwts.builder()
                .subject(admin.email())
                .claim("nombre", admin.nombre())
                .claim("foto", admin.foto())
                .issuedAt(java.util.Date.from(ahora))
                .expiration(java.util.Date.from(ahora.plus(duracion)))
                .signWith(clave)
                .compact();

        return galleta(token, duracion);
    }

    /** La cookie que borra la sesión: misma configuración, vacía y caducada. */
    public ResponseCookie cerrarSesion() {
        return galleta("", Duration.ZERO);
    }

    private ResponseCookie galleta(String valor, Duration duracion) {
        return ResponseCookie.from(COOKIE, valor)
                .httpOnly(true)
                .secure(propiedades.cookieSegura())
                .path("/")
                // Lax y no Strict: con Strict, volver al panel desde un enlace de fuera
                // (un correo, el historial) llegaría sin cookie y parecería sesión
                // caducada. Lax sigue bloqueando el envío en peticiones cruzadas, que
                // es de lo que protege.
                .sameSite("Lax")
                .maxAge(duracion)
                .build();
    }

    /** Lee la cookie y devuelve quién es, si el token vale. */
    public Optional<Administrador> leer(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims datos = Jwts.parser()
                    .verifyWith(clave)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String email = datos.getSubject();

            // La lista blanca se vuelve a mirar en CADA petición, no solo al entrar.
            // Así, quitar un correo de la configuración y reiniciar echa fuera a esa
            // persona al momento, sin esperar a que caduque su token.
            if (!propiedades.permitido(email)) {
                log.warn("Sesión válida pero con un correo ya no permitido: {}", email);
                return Optional.empty();
            }

            return Optional.of(new Administrador(
                    email,
                    datos.get("nombre", String.class),
                    datos.get("foto", String.class)));
        } catch (Exception e) {
            // Caducado, manipulado o de otra clave. Nada que registrar como error:
            // pasa cada vez que una pestaña vieja despierta.
            return Optional.empty();
        }
    }

    /** Un secreto nuevo, para el mensaje de ayuda del arranque. */
    public static String secretoDeEjemplo() {
        byte[] b = new byte[48];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    /** El nombre de la cabecera donde se pone la cookie, para no repetirlo. */
    public static String cabeceraCookie() {
        return HttpHeaders.SET_COOKIE;
    }
}
