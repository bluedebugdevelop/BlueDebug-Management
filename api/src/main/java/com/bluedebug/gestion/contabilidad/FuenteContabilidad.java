package com.bluedebug.gestion.contabilidad;

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
 * La base de datos de la contabilidad.
 *
 * Es la ÚNICA base de datos que el panel escribe como suya. Las de los conectores
 * pertenecen a las apps —VBStats abre su pool de lectura en modo solo lectura
 * justo para no tocarlas—; esta la crea, la llena y la mantiene solo este código,
 * así que aquí sí tiene sentido crear la tabla al vuelo.
 *
 * Vale igual Postgres que MySQL: no hay ni una consulta en todo el paquete que
 * use sintaxis de un motor concreto, y así se puede enchufar a lo que ya haya en
 * el Railway del equipo en vez de obligar a levantar un servicio más.
 *
 * Como en el resto del panel, el bean existe siempre aunque no haya credenciales.
 * Se pregunta por {@link #configurado()} y ya está; nada de repartir
 * {@code @ConditionalOnProperty} por media aplicación.
 */
@Component
@EnableConfigurationProperties(PropiedadesContabilidad.class)
public class FuenteContabilidad {

    private static final Logger log = LoggerFactory.getLogger(FuenteContabilidad.class);

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    /**
     * Si la tabla ya se comprobó en este arranque.
     *
     * La comprobación se hace en la primera consulta y no en el constructor a
     * propósito: si la base de datos está caída al desplegar, el panel tiene que
     * arrancar igual y enseñar la sección apagada, no negarse a levantar.
     */
    private volatile boolean tablaLista;

    public FuenteContabilidad(PropiedadesContabilidad propiedades) {
        if (!propiedades.hayBaseDeDatos()) {
            log.info("Contabilidad: sin BLUEDEBUG_CONTABILIDAD_URL; la sección queda apagada");
            this.pool = null;
            this.jdbc = null;
            return;
        }

        this.pool = montar(propiedades);
        this.jdbc = pool == null ? null : new JdbcTemplate(pool);
        if (jdbc != null) {
            jdbc.setQueryTimeout(15);
        }
    }

    private HikariDataSource montar(PropiedadesContabilidad propiedades) {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(aJdbc(propiedades.url()));
            credenciales(config, propiedades);
            // Dos conexiones sobran: esto lo usan tres personas y muy de vez en cuando.
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setPoolName("contabilidad");
            config.setConnectionTimeout(8_000);
            config.setValidationTimeout(4_000);
            config.setIdleTimeout(30_000);
            // Railway corta las conexiones ociosas; reciclarlas antes evita el clásico
            // fallo en la primera consulta de la mañana.
            config.setMaxLifetime(240_000);
            config.setInitializationFailTimeout(-1);

            return new HikariDataSource(config);
        } catch (Exception e) {
            log.warn("Contabilidad: no se pudo montar el pool: {}", e.getMessage());
            return null;
        }
    }

    public boolean configurado() {
        return jdbc != null;
    }

    /**
     * El acceso a la base, con la tabla ya creada si hacía falta.
     *
     * @throws IllegalStateException si no hay credenciales. Quien llame tiene que
     *                               haber mirado {@link #configurado()} antes.
     */
    public JdbcTemplate jdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("La contabilidad no tiene base de datos configurada");
        }
        prepararTabla();
        return jdbc;
    }

    /**
     * Comprueba de verdad que se puede consultar.
     *
     * Igual que con los conectores: que la variable esté puesta no significa que
     * la clave sea buena. Un fallo aquí tiene que salir como «sección sin datos»
     * y no como una pantalla en blanco.
     */
    public boolean disponible() {
        if (jdbc == null) {
            return false;
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            prepararTabla();
            return true;
        } catch (Exception e) {
            log.warn("Contabilidad: la base de datos no responde: {}", e.getMessage());
            return false;
        }
    }

    /** El motivo que se enseña en pantalla cuando no se puede usar. */
    public String motivo() {
        if (jdbc == null) {
            return "Falta BLUEDEBUG_CONTABILIDAD_URL. Sin ella no hay dónde guardar los gastos.";
        }
        return "La base de datos de la contabilidad no responde. El motivo exacto está en el log.";
    }

    /**
     * Crea la tabla la primera vez.
     *
     * Los tipos están elegidos para que el mismo DDL valga en Postgres y en MySQL:
     * nada de SERIAL, de AUTO_INCREMENT ni de JSON. El id lo pone Java (un UUID),
     * lo que además evita tener que leer la clave generada después de insertar.
     *
     * No se crea ningún índice, y no es un olvido: esto son los gastos de una
     * empresa de tres personas, unos cientos de filas al año. Un índice no
     * ahorraría nada medible, y {@code CREATE INDEX IF NOT EXISTS} no existe en
     * MySQL, así que habría que escribir dos caminos para no ganar nada.
     */
    private void prepararTabla() {
        if (tablaLista) {
            return;
        }
        synchronized (this) {
            if (tablaLista) {
                return;
            }
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS gastos (
                        id           VARCHAR(36)    NOT NULL,
                        fecha        DATE           NOT NULL,
                        concepto     VARCHAR(200)   NOT NULL,
                        categoria    VARCHAR(40)    NOT NULL,
                        proveedor    VARCHAR(120),
                        importe      DECIMAL(12,2)  NOT NULL,
                        iva          DECIMAL(12,2)  NOT NULL,
                        pagado_por   VARCHAR(80)    NOT NULL,
                        app          VARCHAR(40),
                        recurrencia  VARCHAR(20)    NOT NULL,
                        nota         VARCHAR(500),
                        creado_por   VARCHAR(160)   NOT NULL,
                        creado_en    TIMESTAMP      NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            tablaLista = true;
            log.info("Contabilidad: tabla de gastos lista");
        }
    }

    @PreDestroy
    void cerrar() {
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * Convierte la url de Railway en una de JDBC.
     *
     * Railway publica {@code postgresql://usuario:clave@host:puerto/base} (o
     * {@code mysql://...}), que es lo que se copia del panel de Railway de un
     * tirón. El driver de Java no entiende ese formato, y obligar a reescribirlo
     * a mano es pedir que un día se cambie la contraseña en Railway y no aquí.
     */
    static String aJdbc(String url) {
        String limpia = url.trim();
        if (limpia.startsWith("jdbc:")) {
            return limpia;
        }

        URI uri = URI.create(limpia);
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String base = uri.getPath() == null ? "" : uri.getPath();

        if (esquema.startsWith("mysql")) {
            int puerto = uri.getPort() > 0 ? uri.getPort() : 3306;
            return "jdbc:mysql://" + uri.getHost() + ":" + puerto + base
                    + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        }

        // Por defecto, Postgres: es lo que sale al añadir una base de datos en Railway.
        int puerto = uri.getPort() > 0 ? uri.getPort() : 5432;
        return "jdbc:postgresql://" + uri.getHost() + ":" + puerto + base;
    }

    /** Saca usuario y clave de donde estén: dentro de la url o en sus propiedades. */
    private void credenciales(HikariConfig config, PropiedadesContabilidad propiedades) {
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
