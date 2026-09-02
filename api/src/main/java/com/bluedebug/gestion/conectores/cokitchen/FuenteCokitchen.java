package com.bluedebug.gestion.conectores.cokitchen;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * La conexión con la base de datos de Co-Kitchen (Supabase).
 *
 * Es la misma forma que {@link com.bluedebug.gestion.conectores.vbstats.FuenteVbstats}
 * y por los mismos motivos: el bean existe siempre y se pregunta por
 * {@link #disponible()}, para que al panel no le falte una app entera cuando a
 * otra le falta una credencial. Y hay DOS pools, uno de solo lectura para todo lo
 * que consulta el panel y otro de una conexión para lo poco que escribe —hoy,
 * solo el plan de una cuenta y el borrado que se pide expresamente—, porque esta
 * es la base de datos de producción de gente real y no hay ninguna de pruebas
 * debajo.
 *
 * Lo propio de Supabase está en {@link #aJdbc} y en {@link #montar}: el TLS
 * obligatorio y el pooler.
 */
@Component
@EnableConfigurationProperties(PropiedadesCokitchen.class)
public class FuenteCokitchen {

    private static final Logger log = LoggerFactory.getLogger(FuenteCokitchen.class);

    private final HikariDataSource poolLectura;
    private final HikariDataSource poolEscritura;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate jdbcEscritura;

    public FuenteCokitchen(PropiedadesCokitchen propiedades) {
        if (!propiedades.hayBaseDeDatos()) {
            log.info("Co-Kitchen: sin BLUEDEBUG_COKITCHEN_URL; el conector queda apagado");
            this.poolLectura = null;
            this.poolEscritura = null;
            this.jdbc = null;
            this.jdbcEscritura = null;
            return;
        }

        this.poolLectura = montar(propiedades, "cokitchen-lectura", 3, true);
        this.poolEscritura = montar(propiedades, "cokitchen-escritura", 1, false);
        this.jdbc = poolLectura == null ? null : new JdbcTemplate(poolLectura);
        this.jdbcEscritura = poolEscritura == null ? null : new JdbcTemplate(poolEscritura);

        if (jdbc != null) {
            jdbc.setQueryTimeout(15);
        }
        if (jdbcEscritura != null) {
            // El borrado de una cuenta toca varias tablas dentro de una
            // transacción y va contra un servidor que está en otro continente:
            // 15 segundos se quedan cortos justo el día que hace falta.
            jdbcEscritura.setQueryTimeout(30);
        }
    }

    private HikariDataSource montar(PropiedadesCokitchen propiedades, String nombre, int maximo, boolean soloLectura) {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(aJdbc(propiedades.url()));
            credenciales(config, propiedades);
            config.setMaximumPoolSize(maximo);
            config.setMinimumIdle(0);
            config.setPoolName(nombre);
            config.setReadOnly(soloLectura);
            config.setConnectionTimeout(8_000);
            config.setValidationTimeout(4_000);
            config.setIdleTimeout(30_000);
            // El pooler de Supabase corta las conexiones ociosas por su cuenta;
            // reciclarlas antes evita el «connection closed» de la primera
            // consulta de la mañana.
            config.setMaxLifetime(240_000);
            // Sin esto, Hikari abre una conexión al construirse y el arranque del
            // panel se queda esperando a una base de datos que quizá no está.
            config.setInitializationFailTimeout(-1);

            return new HikariDataSource(config);
        } catch (Exception e) {
            log.warn("Co-Kitchen: no se pudo montar el pool '{}': {}", nombre, e.getMessage());
            return null;
        }
    }

    public boolean configurado() {
        return jdbc != null;
    }

    /** Para consultar. Va en solo lectura. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Para lo poco que escribe: el plan de una cuenta y el borrado. */
    public JdbcTemplate jdbcEscritura() {
        return jdbcEscritura;
    }

    /**
     * Comprueba de verdad que se puede consultar.
     *
     * Mirar si la variable está puesta no vale: una contraseña rotada en Supabase
     * tiene que salir como «no disponible» en el menú, no como una pantalla en
     * blanco cuando alguien entra en la pestaña de usuarios.
     */
    public boolean disponible() {
        if (jdbc == null) {
            return false;
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("Co-Kitchen: la base de datos no responde: {}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    void cerrar() {
        if (poolLectura != null) {
            poolLectura.close();
        }
        if (poolEscritura != null) {
            poolEscritura.close();
        }
    }

    /**
     * Convierte la cadena que copia Supabase en una de JDBC.
     *
     * Supabase publica {@code postgresql://postgres.abcdef:clave@aws-0-eu-west-3.pooler.supabase.com:5432/postgres},
     * que es lo que se copia del botón «Connect». El driver de Java no entiende
     * ese formato, y obligar a reescribir la misma credencial a mano es pedir que
     * un día se cambie en un sitio y no en el otro.
     *
     * Se le añaden dos parámetros que no son opcionales aquí:
     *
     *   · {@code sslmode=require} — Supabase rechaza la conexión en claro. Es
     *     «cifra, no verifiques el certificado»: verificarlo pediría meter la CA
     *     de Supabase en el almacén de la JVM, y el contenedor se reconstruye en
     *     cada despliegue.
     *   · {@code prepareThreshold=0} — si la url apunta al pooler en modo
     *     transacción (el puerto 6543), las sentencias preparadas del lado del
     *     servidor NO sobreviven de una transacción a la siguiente, y a la sexta
     *     ejecución de la misma consulta —cuando el driver decide nombrarla— salta
     *     un «prepared statement S_1 does not exist». Es de los errores que no
     *     aparecen en las pruebas y sí a los cinco minutos de uso. Con el umbral a
     *     cero el driver no las nombra nunca, y en una conexión directa (5432)
     *     esto no cuesta nada medible para las consultas de un panel.
     */
    static String aJdbc(String url) {
        String limpia = url.trim();
        if (limpia.startsWith("jdbc:")) {
            return limpia;
        }

        URI uri = URI.create(limpia);
        String base = uri.getPath() == null || uri.getPath().isBlank() ? "/postgres" : uri.getPath();
        int puerto = uri.getPort() > 0 ? uri.getPort() : 5432;

        return "jdbc:postgresql://" + uri.getHost() + ":" + puerto + base
                + "?sslmode=require&prepareThreshold=0";
    }

    /** Saca usuario y clave de donde estén: dentro de la url o en sus propiedades. */
    private void credenciales(HikariConfig config, PropiedadesCokitchen propiedades) {
        String url = propiedades.url().trim();

        if (!url.startsWith("jdbc:")) {
            String userInfo = URI.create(url).getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                int corte = userInfo.indexOf(':');
                if (corte >= 0) {
                    config.setUsername(decodificar(userInfo.substring(0, corte)));
                    config.setPassword(decodificar(userInfo.substring(corte + 1)));
                    return;
                }
                config.setUsername(decodificar(userInfo));
                return;
            }
        }

        if (propiedades.usuario() != null && !propiedades.usuario().isBlank()) {
            config.setUsername(propiedades.usuario());
            config.setPassword(propiedades.clave());
        }
    }

    /** Las contraseñas de Supabase llevan símbolos y viajan percent-encoded en la url. */
    private String decodificar(String valor) {
        return URLDecoder.decode(valor, StandardCharsets.UTF_8);
    }
}
