package com.bluedebug.gestion.conectores.vbstats;

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
 * La conexión con la base de datos de VBStats.
 *
 * Se envuelve el pool en un componente propio, en vez de publicar un
 * {@code DataSource} suelto, por una razón concreta: el panel tiene que arrancar
 * y funcionar aunque a VBStats le falten las credenciales. Con beans normales eso
 * obliga a repartir {@code @ConditionalOnProperty} y {@code ObjectProvider} por
 * media aplicación; así, el bean existe siempre, se pregunta por
 * {@link #disponible()} y se acabó.
 *
 * HAY DOS POOLS, Y LA SEPARACIÓN ES EL PUNTO
 *
 * Esta es la base de datos de producción de VBStats: aquí están las cuentas y los
 * partidos de gente real, y no hay ningún entorno de pruebas debajo. Por eso el
 * pool que usan las consultas del panel —que son casi todo lo que hace— va en
 * modo SOLO LECTURA: si un día alguien escribe un UPDATE en una pantalla de
 * estadísticas, salta el driver y no los datos.
 *
 * Escribir se escribe por el otro pool, {@link #jdbcEscritura()}, de una sola
 * conexión y con dos únicos usuarios en todo el código: la fila del historial al
 * mandar una notificación, y el borrado de cuenta que se pide expresamente desde
 * el panel. Cualquier cosa nueva que lo use tendría que ser igual de deliberada.
 *
 * El pool de lectura es DIMINUTO (tres conexiones) también a propósito: este
 * panel lo usa una persona, mientras que el backend de VBStats atiende a toda la
 * app con un pool de diez contra el mismo servidor. Reservar aquí un pool grande
 * sería quitarle conexiones a la app de verdad para tenerlas paradas.
 */
@Component
@EnableConfigurationProperties(PropiedadesVbstats.class)
public class FuenteVbstats {

    private static final Logger log = LoggerFactory.getLogger(FuenteVbstats.class);

    private final HikariDataSource poolLectura;
    private final HikariDataSource poolEscritura;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate jdbcEscritura;

    public FuenteVbstats(PropiedadesVbstats propiedades) {
        if (!propiedades.hayBaseDeDatos()) {
            log.info("VBStats: sin BLUEDEBUG_VBSTATS_URL; el conector queda apagado");
            this.poolLectura = null;
            this.poolEscritura = null;
            this.jdbc = null;
            this.jdbcEscritura = null;
            return;
        }

        this.poolLectura = montar(propiedades, "vbstats-lectura", 3, true);
        this.poolEscritura = montar(propiedades, "vbstats-escritura", 1, false);
        this.jdbc = poolLectura == null ? null : new JdbcTemplate(poolLectura);
        this.jdbcEscritura = poolEscritura == null ? null : new JdbcTemplate(poolEscritura);

        // Tope por consulta. El `connectionTimeout` del pool cubre el abrir la
        // conexión, pero no una consulta ya lanzada que se queda esperando al
        // servidor: sin esto, una tabla bloqueada al otro lado deja colgada la
        // petición del panel indefinidamente.
        if (jdbc != null) {
            jdbc.setQueryTimeout(15);
        }
        if (jdbcEscritura != null) {
            jdbcEscritura.setQueryTimeout(15);
        }
    }

    private HikariDataSource montar(PropiedadesVbstats propiedades, String nombre, int maximo, boolean soloLectura) {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(aJdbc(propiedades.url()));
            credenciales(config, propiedades);
            config.setMaximumPoolSize(maximo);
            config.setMinimumIdle(0);
            config.setPoolName(nombre);
            config.setReadOnly(soloLectura);
            // Una base remota que no responde no puede dejar colgado al panel: se
            // prefiere fallar rápido y marcar la app como caída.
            config.setConnectionTimeout(8_000);
            config.setValidationTimeout(4_000);
            config.setIdleTimeout(30_000);
            // Railway corta las conexiones ociosas; reciclarlas antes evita el clásico
            // «Communications link failure» en la primera consulta de la mañana.
            config.setMaxLifetime(240_000);
            // Sin esto, Hikari intenta abrir una conexión al construirse y el arranque
            // del panel se queda esperando a una base de datos que quizá no está.
            config.setInitializationFailTimeout(-1);

            return new HikariDataSource(config);
        } catch (Exception e) {
            log.warn("VBStats: no se pudo montar el pool '{}': {}", nombre, e.getMessage());
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

    /**
     * Para los dos sitios que escriben. Léase el bloque de arriba antes de
     * usarlo en un tercero.
     */
    public JdbcTemplate jdbcEscritura() {
        return jdbcEscritura;
    }

    /**
     * Comprueba de verdad que se puede consultar.
     *
     * Mirar si las variables de entorno están puestas no vale: una clave mal
     * copiada tiene que salir como «no disponible» en el panel, no como una
     * pantalla en blanco cuando alguien entra en la pestaña de usuarios.
     */
    public boolean disponible() {
        if (jdbc == null) {
            return false;
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("VBStats: la base de datos no responde: {}", e.getMessage());
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
     * Convierte la url de Railway en una de JDBC.
     *
     * Railway publica {@code mysql://usuario:clave@host:puerto/base}, que es lo
     * que ya está copiado en las variables del backend de VBStats. El driver de
     * Java no entiende ese formato, y obligar a mantener la misma credencial
     * escrita de dos maneras distintas es pedir que un día se cambie una y no la
     * otra. Se traduce aquí.
     */
    static String aJdbc(String url) {
        String limpia = url.trim();
        if (limpia.startsWith("jdbc:")) {
            return limpia;
        }

        URI uri = URI.create(limpia);
        String base = uri.getPath() == null ? "" : uri.getPath();
        int puerto = uri.getPort() > 0 ? uri.getPort() : 3306;

        return "jdbc:mysql://" + uri.getHost() + ":" + puerto + base
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /** Saca usuario y clave de donde estén: dentro de la url o en sus propiedades. */
    private void credenciales(HikariConfig config, PropiedadesVbstats propiedades) {
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

    /** Las contraseñas generadas llevan símbolos y viajan percent-encoded en la url. */
    private String decodificar(String valor) {
        return URLDecoder.decode(valor, StandardCharsets.UTF_8);
    }
}
