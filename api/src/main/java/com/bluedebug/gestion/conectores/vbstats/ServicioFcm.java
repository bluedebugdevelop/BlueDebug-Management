package com.bluedebug.gestion.conectores.vbstats;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Manda las notificaciones de VBStats por Firebase Cloud Messaging.
 *
 * VBStats es React Native con {@code @react-native-firebase/messaging}, así que
 * sus tokens son de FCM y se les habla directamente desde aquí. Es la misma vía
 * que usa su propio backend: las credenciales son las mismas
 * ({@code FIREBASE_SERVICE_ACCOUNT_BASE64}) y el aviso le llega igual al móvil
 * venga de donde venga.
 *
 * Ojo con no confundirlo con CVO, que es Expo y va por otro sitio del todo. Ver
 * {@code ServicioPushExpo}.
 */
@Service
public class ServicioFcm {

    private static final Logger log = LoggerFactory.getLogger(ServicioFcm.class);

    /** FCM no acepta más de 500 destinatarios por llamada. */
    private static final int TAMANO_LOTE = 500;

    private static final String NOMBRE_APP = "bluedebug-vbstats";

    private final FirebaseApp app;

    public ServicioFcm(PropiedadesVbstats propiedades) {
        this.app = arrancar(propiedades);
    }

    private FirebaseApp arrancar(PropiedadesVbstats propiedades) {
        if (!propiedades.hayFirebase()) {
            log.info("VBStats: sin credenciales de Firebase; no se podrán mandar notificaciones");
            return null;
        }
        try {
            byte[] json = Base64.getDecoder().decode(propiedades.firebaseJson().trim());
            GoogleCredentials credenciales = GoogleCredentials.fromStream(new ByteArrayInputStream(json));

            // La app va con nombre propio porque en este mismo proceso vive también la
            // de CVO, que es otro proyecto de Firebase distinto. Con la instancia por
            // defecto solo cabría una de las dos.
            FirebaseOptions opciones = FirebaseOptions.builder().setCredentials(credenciales).build();

            return FirebaseApp.getApps().stream()
                    .filter(a -> NOMBRE_APP.equals(a.getName()))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(opciones, NOMBRE_APP));
        } catch (Exception e) {
            log.warn("VBStats: las credenciales de Firebase no valen: {}", e.getMessage());
            return null;
        }
    }

    public boolean configurado() {
        return app != null;
    }

    /**
     * Lo que pasó al mandar.
     *
     * @param entregados     a cuántos llegó.
     * @param fallidos       cuántos fallaron por cualquier motivo.
     * @param tokensCaducados los que FCM dice que ya no existen, para borrarlos.
     */
    public record Envio(int entregados, int fallidos, List<String> tokensCaducados) {}

    public Envio enviar(List<String> tokens, String titulo, String cuerpo) {
        if (app == null) {
            throw new IllegalStateException("Firebase no está configurado para VBStats");
        }

        int entregados = 0;
        int fallidos = 0;
        List<String> caducados = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i += TAMANO_LOTE) {
            List<String> lote = tokens.subList(i, Math.min(tokens.size(), i + TAMANO_LOTE));

            MulticastMessage mensaje = MulticastMessage.builder()
                    .addAllTokens(lote)
                    .setNotification(Notification.builder().setTitle(titulo).setBody(cuerpo).build())
                    // La app usa `type` para saber qué hacer al tocar el aviso; se manda
                    // el mismo valor que pone su propio panel para que se comporte igual.
                    .putAllData(Map.of("type", "admin"))
                    .build();

            BatchResponse respuesta;
            try {
                respuesta = FirebaseMessaging.getInstance(app).sendEachForMulticast(mensaje);
            } catch (FirebaseMessagingException e) {
                // Falla el lote entero: se cuenta como fallo y se sigue con el
                // siguiente, en vez de tirar el envío completo por un lote malo.
                log.error("VBStats: falló un lote de notificaciones", e);
                fallidos += lote.size();
                continue;
            }

            entregados += respuesta.getSuccessCount();
            fallidos += respuesta.getFailureCount();

            List<SendResponse> resultados = respuesta.getResponses();
            for (int j = 0; j < resultados.size(); j++) {
                FirebaseMessagingException fallo = resultados.get(j).getException();
                if (fallo == null) {
                    continue;
                }
                MessagingErrorCode codigo = fallo.getMessagingErrorCode();
                // UNREGISTERED es «ese móvil ya no tiene la app»; INVALID_ARGUMENT, un
                // token con una forma que ya no vale. Los demás errores pueden ser
                // temporales (una caída de FCM) y borrar por ellos sería cargarse
                // dispositivos vivos.
                if (codigo == MessagingErrorCode.UNREGISTERED || codigo == MessagingErrorCode.INVALID_ARGUMENT) {
                    caducados.add(lote.get(j));
                }
            }
        }

        return new Envio(entregados, fallidos, caducados);
    }

    /** El proyecto de Firebase contra el que se está hablando, para el panel de ajustes. */
    public String proyecto() {
        if (app == null) {
            return null;
        }
        return app.getOptions().getProjectId();
    }
}
