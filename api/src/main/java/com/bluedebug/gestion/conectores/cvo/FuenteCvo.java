package com.bluedebug.gestion.conectores.cvo;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * La conexión con el proyecto de Firebase del club.
 *
 * Se inicializa como una app CON NOMBRE, igual que la de VBStats. Son dos
 * proyectos de Firebase distintos viviendo en el mismo proceso, y la instancia
 * por defecto solo admite uno: si las dos se registraran sin nombre, la segunda
 * se estrellaría al arrancar o —peor— acabaríamos mandando los avisos del club a
 * los móviles de la otra app.
 */
@Component
@EnableConfigurationProperties(PropiedadesCvo.class)
public class FuenteCvo {

    private static final Logger log = LoggerFactory.getLogger(FuenteCvo.class);

    private static final String NOMBRE_APP = "bluedebug-cvo";

    private final FirebaseApp app;

    public FuenteCvo(PropiedadesCvo propiedades) {
        this.app = arrancar(propiedades);
    }

    private FirebaseApp arrancar(PropiedadesCvo propiedades) {
        if (!propiedades.hayFirebase()) {
            log.info("CVO: sin BLUEDEBUG_CVO_FIREBASE; el conector queda apagado");
            return null;
        }
        try {
            byte[] json = Base64.getDecoder().decode(propiedades.firebaseJson().trim());
            GoogleCredentials credenciales = GoogleCredentials.fromStream(new ByteArrayInputStream(json));
            FirebaseOptions opciones = FirebaseOptions.builder().setCredentials(credenciales).build();

            return FirebaseApp.getApps().stream()
                    .filter(a -> NOMBRE_APP.equals(a.getName()))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(opciones, NOMBRE_APP));
        } catch (Exception e) {
            log.warn("CVO: las credenciales de Firebase no valen: {}", e.getMessage());
            return null;
        }
    }

    public boolean configurado() {
        return app != null;
    }

    public Firestore firestore() {
        return app == null ? null : FirestoreClient.getFirestore(app);
    }

    public FirebaseAuth auth() {
        return app == null ? null : FirebaseAuth.getInstance(app);
    }

    public String proyecto() {
        return app == null ? null : app.getOptions().getProjectId();
    }

    /** Una lectura de verdad, para distinguir «mal configurado» de «no configurado». */
    public boolean disponible() {
        if (app == null) {
            return false;
        }
        try {
            firestore().collection("usuarios").limit(1).get().get();
            return true;
        } catch (InterruptedException e) {
            // Restaurar la marca es obligatorio: tragarse una interrupción deja al
            // hilo creyendo que nadie le ha pedido que pare.
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("CVO: Firestore no responde: {}", e.getMessage());
            return false;
        }
    }
}
