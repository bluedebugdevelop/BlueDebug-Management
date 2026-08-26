package com.bluedebug.gestion.seguridad;

import com.bluedebug.gestion.comun.AccesoDenegado;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Comprueba que el {@code id_token} que manda el botón de Google es de verdad.
 *
 * ESTO NO ES DECORATIVO. El navegador manda una cadena y dice «soy esta
 * persona»; sin esta verificación cualquiera podría escribir a mano un JSON con
 * {@code email: bluedebug.develop@gmail.com} y tener el panel entero. El
 * verificador de Google hace tres cosas que hay que hacer todas:
 *
 *   1. Comprueba la FIRMA contra las claves públicas de Google (las descarga y
 *      las cachea él solo, por eso el bean se crea una vez y se reutiliza).
 *   2. Comprueba que el destinatario (`aud`) es NUESTRO client id, y no el de
 *      otra web —si no, un token robado de cualquier otro sitio valdría aquí.
 *   3. Comprueba que no ha caducado.
 *
 * Encima de eso se exige {@code email_verified}, porque un correo sin verificar
 * en un proveedor de identidad es solo un texto que alguien escribió.
 */
@Component
public class VerificadorGoogle {

    private static final Logger log = LoggerFactory.getLogger(VerificadorGoogle.class);

    private final PropiedadesSeguridad propiedades;
    private final GoogleIdTokenVerifier verificador;

    public VerificadorGoogle(PropiedadesSeguridad propiedades) {
        this.propiedades = propiedades;
        this.verificador = propiedades.googleConfigurado()
                ? new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(Collections.singletonList(propiedades.googleClientId()))
                        .build()
                : null;
    }

    /**
     * Devuelve al administrador si el token es válido Y su correo está en la
     * lista blanca. En cualquier otro caso, excepción.
     */
    public Administrador verificar(String idToken) {
        if (verificador == null) {
            throw new AccesoDenegado(
                    "El servidor no tiene configurado BLUEDEBUG_GOOGLE_CLIENT_ID, así que no puede validar el acceso.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new AccesoDenegado("Falta el token de Google");
        }

        GoogleIdToken token;
        try {
            token = verificador.verify(idToken);
        } catch (Exception e) {
            // Un fallo de red al bajar las claves de Google entra por aquí igual que
            // una firma inválida. Se registra el motivo real y se responde igual de
            // parco: por fuera, un token que no vale es un token que no vale.
            log.warn("No se pudo verificar el token de Google: {}", e.getMessage());
            throw new AccesoDenegado("No se pudo verificar el acceso con Google");
        }

        if (token == null) {
            throw new AccesoDenegado("El token de Google no es válido");
        }

        GoogleIdToken.Payload datos = token.getPayload();
        String email = datos.getEmail();

        if (!Boolean.TRUE.equals(datos.getEmailVerified())) {
            throw new AccesoDenegado("Esa cuenta de Google no tiene el correo verificado");
        }
        if (!propiedades.permitido(email)) {
            // Se deja constancia del intento: es la única señal de que alguien está
            // probando a entrar, y sin ella no habría forma de enterarse.
            log.warn("Intento de acceso denegado para {}", email);
            throw new AccesoDenegado("Esta cuenta no tiene acceso al panel");
        }

        return new Administrador(
                email.trim().toLowerCase(),
                (String) datos.get("name"),
                (String) datos.get("picture"));
    }
}
