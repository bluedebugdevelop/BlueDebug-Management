package com.bluedebug.gestion.conectores.cokitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La traducción de la cadena de conexión de Supabase a una de JDBC.
 *
 * Existe para que lo que copia el botón «Connect» de Supabase se pueda pegar en
 * la variable tal cual. Si esto se rompe, el síntoma es un conector apagado con
 * un mensaje genérico, y se pierde un buen rato buscando en el sitio equivocado.
 */
class UrlCokitchenTest {

    @Test
    @DisplayName("la cadena del pooler de Supabase se convierte en una de JDBC")
    void urlDeSupabase() {
        String jdbc = FuenteCokitchen.aJdbc(
                "postgresql://postgres.abcdefghijkl:clave@aws-0-eu-west-3.pooler.supabase.com:6543/postgres");

        assertTrue(jdbc.startsWith("jdbc:postgresql://aws-0-eu-west-3.pooler.supabase.com:6543/postgres"), jdbc);
    }

    @Test
    @DisplayName("siempre se pide TLS: Supabase rechaza la conexión en claro")
    void siempreConTls() {
        String jdbc = FuenteCokitchen.aJdbc("postgresql://postgres:clave@db.abcdef.supabase.co:5432/postgres");
        assertTrue(jdbc.contains("sslmode=require"), jdbc);
    }

    /**
     * Ver el porqué en {@link FuenteCokitchen#aJdbc}: contra el pooler en modo
     * transacción, una sentencia preparada con nombre revienta a la sexta vez.
     */
    @Test
    @DisplayName("las sentencias preparadas no se nombran nunca")
    void sinSentenciasPreparadasConNombre() {
        String jdbc = FuenteCokitchen.aJdbc("postgresql://postgres:clave@host:6543/postgres");
        assertTrue(jdbc.contains("prepareThreshold=0"), jdbc);
    }

    @Test
    @DisplayName("si falta el puerto se usa el 5432")
    void puertoPorDefecto() {
        String jdbc = FuenteCokitchen.aJdbc("postgresql://postgres:clave@db.abcdef.supabase.co/postgres");
        assertTrue(jdbc.startsWith("jdbc:postgresql://db.abcdef.supabase.co:5432/postgres"), jdbc);
    }

    @Test
    @DisplayName("si falta la base de datos se usa postgres")
    void basePorDefecto() {
        String jdbc = FuenteCokitchen.aJdbc("postgresql://postgres:clave@host:5432");
        assertTrue(jdbc.startsWith("jdbc:postgresql://host:5432/postgres"), jdbc);
    }

    @Test
    @DisplayName("una url que ya es de JDBC se deja como está")
    void jdbcSeRespeta() {
        String original = "jdbc:postgresql://localhost:5432/postgres?sslmode=disable";
        assertEquals(original, FuenteCokitchen.aJdbc(original));
    }

    @Test
    @DisplayName("los espacios de un copiar y pegar no rompen la conexión")
    void espaciosSobrantes() {
        String jdbc = FuenteCokitchen.aJdbc("  postgresql://postgres:clave@host:5432/postgres  ");
        assertTrue(jdbc.startsWith("jdbc:postgresql://host:5432/postgres"), jdbc);
    }
}
