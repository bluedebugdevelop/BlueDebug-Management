package com.bluedebug.gestion.conectores.vbstats;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las credenciales con las que el panel llega a VBStats.
 *
 * @param url             la base de datos MySQL. Se admiten las dos formas que hay
 *                        por ahí: la de Railway ({@code mysql://usuario:clave@host:puerto/base})
 *                        y la de JDBC ({@code jdbc:mysql://host:puerto/base}). Ver
 *                        {@link FuenteVbstats#aJdbc}.
 * @param usuario         solo si la url es de tipo JDBC y no lleva credenciales dentro.
 * @param clave           íd.
 * @param stripeClave     clave secreta de Stripe. Con una restringida de solo lectura
 *                        basta y sobra: el panel lee cobros, nunca cobra.
 * @param firebaseJson    credenciales de la cuenta de servicio de Firebase, en base64,
 *                        el mismo formato que ya usa el backend de VBStats para FCM.
 * @param appleIssuerId   el «Issuer ID» de App Store Connect (un UUID). Es el mismo
 *                        para todas las claves de la cuenta.
 * @param appleKeyId      el identificador de la clave concreta.
 * @param appleClave      el contenido del fichero .p8, con sus cabeceras PEM o en
 *                        base64 de una línea.
 * @param appleBundleId   el bundle de la app: {@code com.vbstats...}. Apple lo exige
 *                        dentro del token y rechaza el que no cuadre.
 * @param applePruebas    true para hablar con el entorno de sandbox en vez de con el
 *                        de producción.
 */
@ConfigurationProperties(prefix = "bluedebug.vbstats")
public record PropiedadesVbstats(
        String url,
        String usuario,
        String clave,
        String stripeClave,
        String firebaseJson,
        String appleIssuerId,
        String appleKeyId,
        String appleClave,
        String appleBundleId,
        boolean applePruebas
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

    /** Las cuatro piezas de Apple van juntas: con tres no se puede firmar nada. */
    public boolean hayAppStore() {
        return lleno(appleIssuerId) && lleno(appleKeyId) && lleno(appleClave) && lleno(appleBundleId);
    }

    private boolean lleno(String valor) {
        return valor != null && !valor.isBlank();
    }
}
