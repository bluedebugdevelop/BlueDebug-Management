package com.bluedebug.gestion.conectores.cvo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las credenciales con las que el panel llega al Club Voleibol Oviedo.
 *
 * @param firebaseJson cuenta de servicio del proyecto {@code cv-oviedo}, en base64.
 *                     Se saca de la consola de Firebase → Configuración del
 *                     proyecto → Cuentas de servicio → Generar clave privada, y se
 *                     codifica con {@code base64 -w0 clave.json}.
 * @param webUrl       la web del club, para el enlace del panel. Solo informativo.
 */
@ConfigurationProperties(prefix = "bluedebug.cvo")
public record PropiedadesCvo(String firebaseJson, String webUrl) {

    public boolean hayFirebase() {
        return firebaseJson != null && !firebaseJson.isBlank();
    }
}
