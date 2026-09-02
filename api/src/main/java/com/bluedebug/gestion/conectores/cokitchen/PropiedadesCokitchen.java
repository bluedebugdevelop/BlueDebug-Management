package com.bluedebug.gestion.conectores.cokitchen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las credenciales con las que el panel llega a Co-Kitchen.
 *
 * Co-Kitchen vive en Supabase, y a Supabase se le puede hablar de dos maneras:
 * por su API REST con la clave de servicio, o por Postgres a pelo. Aquí se entra
 * por Postgres, y no es por comodidad: las cuentas de Co-Kitchen están en
 * {@code auth.users}, un esquema que la API REST NO expone. Sin conexión directa
 * no hay forma de leer ni el correo ni el último acceso, que es justo lo que este
 * conector viene a enseñar.
 *
 * @param url     la cadena de conexión. Se admiten las dos formas: la que copia
 *                Supabase ({@code postgresql://usuario:clave@host:puerto/postgres})
 *                y la de JDBC. Ver {@link FuenteCokitchen#aJdbc}.
 * @param usuario solo si la url es de tipo JDBC y no lleva credenciales dentro.
 * @param clave   íd.
 */
@ConfigurationProperties(prefix = "bluedebug.cokitchen")
public record PropiedadesCokitchen(String url, String usuario, String clave) {

    public boolean hayBaseDeDatos() {
        return url != null && !url.isBlank();
    }
}
