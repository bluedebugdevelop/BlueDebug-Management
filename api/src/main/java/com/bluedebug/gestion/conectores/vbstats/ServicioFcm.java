package com.bluedebug.gestion.conectores.vbstats;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
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
import java.util.TreeMap;

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

    /** Por qué no se puede enviar, dicho para quien lo lee en el panel. Nulo si se puede. */
    private String problema;

    public ServicioFcm(PropiedadesVbstats propiedades) {
        this.app = arrancar(propiedades);
    }

    private FirebaseApp arrancar(PropiedadesVbstats propiedades) {
        if (!propiedades.hayFirebase()) {
            log.info("VBStats: sin credenciales de Firebase; no se podrán mandar notificaciones");
            problema = "Falta BLUEDEBUG_VBSTATS_FIREBASE con la cuenta de servicio; sin ella no se puede enviar.";
            return null;
        }
        try {
            byte[] json = Base64.getDecoder().decode(propiedades.firebaseJson().trim());
            GoogleCredentials credenciales = GoogleCredentials.fromStream(new ByteArrayInputStream(json));

            // Se firma algo en local antes de dar el servicio por bueno. Una clave
            // puede leerse sin error y no saber firmar: pasó con una a la que se le
            // había corrompido un parámetro CRT (dp). Java la rechaza en cada firma;
            // Node la acepta porque recalcula sin CRT, así que el backend de VBStats
            // mandaba bien y el panel fallaba los 49 envíos en silencio.
            if (credenciales instanceof ServiceAccountCredentials cuenta) {
                try {
                    cuenta.sign("comprobacion".getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    problema = "La clave privada de BLUEDEBUG_VBSTATS_FIREBASE no sabe firmar (está corrupta). "
                            + "Genera una nueva en Firebase › Cuentas de servicio y sustitúyela.";
                    log.error("VBStats: {}", problema, e);
                    return null;
                }
            }

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
            problema = "BLUEDEBUG_VBSTATS_FIREBASE no se puede leer como cuenta de servicio: " + e.getMessage();
            return null;
        }
    }

    public boolean configurado() {
        return app != null;
    }

    public String problema() {
        return problema;
    }

    /**
     * Lo que pasó al mandar.
     *
     * @param entregados     a cuántos llegó.
     * @param fallidos       cuántos fallaron por cualquier motivo.
     * @param tokensCaducados los que FCM dice que ya no existen, para borrarlos.
     * @param motivos        código de error de FCM → cuántas veces. Sin esto, un
     *                       envío que no llega a nadie queda como «0» en el
     *                       historial y no hay forma de saber por qué.
     */
    public record Envio(int entregados, int fallidos, List<String> tokensCaducados, Map<String, Integer> motivos) {}

    public Envio enviar(List<String> tokens, String titulo, String cuerpo) {
        if (app == null) {
            throw new IllegalStateException("Firebase no está configurado para VBStats");
        }

        int entregados = 0;
        int fallidos = 0;
        List<String> caducados = new ArrayList<>();
        Map<String, Integer> motivos = new TreeMap<>();
        // Un ejemplo del mensaje completo por código: el código solo a veces no
        // basta («INVALID_ARGUMENT» dice poco sin la frase de Google detrás).
        Map<String, String> ejemplos = new TreeMap<>();

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
                motivos.merge(codigoDe(e), lote.size(), Integer::sum);
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
                String motivo = codigoDe(fallo);
                motivos.merge(motivo, 1, Integer::sum);
                ejemplos.putIfAbsent(motivo, fallo.getMessage());

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

        if (!motivos.isEmpty()) {
            log.warn("VBStats push: entregados={} fallidos={} motivos={} ejemplos={}",
                    entregados, fallidos, motivos, ejemplos);
        }

        return new Envio(entregados, fallidos, caducados, motivos);
    }

    /**
     * El nombre del error tal cual lo da FCM, que es lo que se puede buscar. Cuando
     * no trae código de mensajería —fallos de credenciales, de red— se usa el
     * genérico del SDK, que es donde aparece p. ej. una clave de servicio revocada.
     */
    private static String codigoDe(FirebaseMessagingException e) {
        if (e.getMessagingErrorCode() != null) {
            return e.getMessagingErrorCode().name();
        }
        return e.getErrorCode() != null ? e.getErrorCode().name() : "DESCONOCIDO";
    }

    /** El proyecto de Firebase contra el que se está hablando, para el panel de ajustes. */
    public String proyecto() {
        if (app == null) {
            return null;
        }
        return app.getOptions().getProjectId();
    }
}
