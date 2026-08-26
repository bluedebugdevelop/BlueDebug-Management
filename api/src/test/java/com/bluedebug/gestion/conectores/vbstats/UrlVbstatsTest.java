package com.bluedebug.gestion.conectores.vbstats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La traducción de la url de Railway a una de JDBC.
 *
 * Existe para que la misma credencial que ya está en las variables de VBStats se
 * pueda pegar aquí tal cual. Si esto se rompe, el síntoma es un conector apagado
 * con un mensaje genérico, y se pierde un buen rato buscando en el sitio
 * equivocado.
 */
class UrlVbstatsTest {

    @Test
    @DisplayName("la url de Railway se convierte en una de JDBC")
    void urlDeRailway() {
        String jdbc = FuenteVbstats.aJdbc("mysql://root:clave@interchange.proxy.rlwy.net:41234/railway");

        assertTrue(jdbc.startsWith("jdbc:mysql://interchange.proxy.rlwy.net:41234/railway"), jdbc);
        assertTrue(jdbc.contains("connectionTimeZone=UTC"), jdbc);
    }

    @Test
    @DisplayName("si falta el puerto se usa el 3306")
    void puertoPorDefecto() {
        String jdbc = FuenteVbstats.aJdbc("mysql://root:clave@localhost/vbstats");
        assertTrue(jdbc.startsWith("jdbc:mysql://localhost:3306/vbstats"), jdbc);
    }

    @Test
    @DisplayName("una url que ya es de JDBC se deja como está")
    void jdbcSeRespeta() {
        String original = "jdbc:mysql://localhost:3306/vbstats?useSSL=true";
        assertTrue(FuenteVbstats.aJdbc(original).equals(original));
    }

    @Test
    @DisplayName("los espacios de un copiar y pegar no rompen la conexión")
    void espaciosSobrantes() {
        String jdbc = FuenteVbstats.aJdbc("  mysql://root:clave@host:3306/base  ");
        assertTrue(jdbc.startsWith("jdbc:mysql://host:3306/base"), jdbc);
    }
}
