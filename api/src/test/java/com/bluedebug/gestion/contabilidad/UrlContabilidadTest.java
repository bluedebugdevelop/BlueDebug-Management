package com.bluedebug.gestion.contabilidad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La traducción de la url de la base de datos de gastos.
 *
 * Aquí hay un motor más que en VBStats: esta base la elige quien despliega y lo
 * normal es que sea el Postgres que Railway ofrece por defecto. Si la detección
 * del esquema falla, el síntoma es feo de diagnosticar —un driver intentando
 * hablar el protocolo del otro— así que se comprueba por separado.
 */
class UrlContabilidadTest {

    @Test
    @DisplayName("la url de Postgres de Railway se convierte en una de JDBC")
    void postgresDeRailway() {
        String jdbc = FuenteContabilidad.aJdbc("postgresql://postgres:clave@monorail.proxy.rlwy.net:42315/railway");

        assertEquals("jdbc:postgresql://monorail.proxy.rlwy.net:42315/railway", jdbc);
    }

    @Test
    @DisplayName("también vale el esquema corto postgres://")
    void postgresCorto() {
        String jdbc = FuenteContabilidad.aJdbc("postgres://usuario:clave@host:5432/gastos");
        assertEquals("jdbc:postgresql://host:5432/gastos", jdbc);
    }

    @Test
    @DisplayName("si la url es de MySQL se usa su driver, no el de Postgres")
    void mysqlSeReconoce() {
        String jdbc = FuenteContabilidad.aJdbc("mysql://root:clave@host:41234/gastos");

        assertTrue(jdbc.startsWith("jdbc:mysql://host:41234/gastos"), jdbc);
        assertTrue(jdbc.contains("connectionTimeZone=UTC"), jdbc);
    }

    @Test
    @DisplayName("sin puerto, cada motor usa el suyo")
    void puertosPorDefecto() {
        assertEquals("jdbc:postgresql://host:5432/gastos",
                FuenteContabilidad.aJdbc("postgresql://u:c@host/gastos"));
        assertTrue(FuenteContabilidad.aJdbc("mysql://u:c@host/gastos")
                .startsWith("jdbc:mysql://host:3306/gastos"));
    }

    @Test
    @DisplayName("una url que ya es de JDBC se deja como está")
    void jdbcSeRespeta() {
        String original = "jdbc:postgresql://localhost:5432/gastos?sslmode=require";
        assertEquals(original, FuenteContabilidad.aJdbc(original));
    }

    @Test
    @DisplayName("los espacios de un copiar y pegar no rompen la conexión")
    void espaciosSobrantes() {
        assertEquals("jdbc:postgresql://host:5432/gastos",
                FuenteContabilidad.aJdbc("  postgresql://u:c@host:5432/gastos  "));
    }
}
