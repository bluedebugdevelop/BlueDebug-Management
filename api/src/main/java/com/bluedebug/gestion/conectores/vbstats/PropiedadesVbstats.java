package com.bluedebug.gestion.conectores.vbstats;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las credenciales con las que el panel llega a VBStats.
 *
 * @param url          la base de datos MySQL. Se admiten las dos formas que hay
 *                     por ahí: la de Railway ({@code mysql://usuario:clave@host:puerto/base})
 *                     y la de JDBC ({@code jdbc:mysql://host:puerto/base}). Ver
 *                     {@link ConfiguracionVbstats#aJdbc}.
 * @param usuario      solo si la url es de tipo JDBC y no lleva credenciales dentro.
 * @param clave        íd.
 * @param stripeClave  clave secreta de Stripe. Con una restringida de solo lectura
 *                     basta y sobra: el panel lee cobros, nunca cobra.
 * @param firebaseJson credenciales de la cuenta de servicio de Firebase, en base64,
 *                     el mismo formato que ya usa el backend de VBStats para FCM.
 */
@ConfigurationProperties(prefix = "bluedebug.vbstats")
public record PropiedadesVbstats(
        String url,
        String usuario,
        String clave,
        String stripeClave,
        String firebaseJson
) {
    public boolean hayBaseDeDatos() {
        return url != null && !url.isBlank();
    }

    public boolean hayStripe() {
        return stripeClave != null && !stripeClave.isBlank();
    }

    public boolean hayFirebase() {
        return firebaseJson != null && !firebaseJson.isBlank();
    }
}
