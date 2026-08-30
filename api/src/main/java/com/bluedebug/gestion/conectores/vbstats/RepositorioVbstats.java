package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Las consultas contra la base de datos de VBStats.
 *
 * Sobre agrupar por día: se traen los instantes en crudo y se agrupan en Java,
 * en vez de hacer un {@code GROUP BY DATE(created_at)}. Cuesta una línea más y
 * evita un error que no se ve hasta que alguien lo mira con lupa: MySQL guarda
 * UTC, y agrupar por día en UTC manda todo lo que pasa entre medianoche y las
 * dos de la mañana en España al día anterior. Con {@code CONVERT_TZ} tampoco se
 * arregla del todo, porque el desfase cambia con el horario de verano. Java sí
 * sabe de husos, así que la conversión se hace ahí ({@link Rango#diaDe}).
 *
 * Los volúmenes lo permiten: son altas y partidos de un periodo, cientos de
 * filas. Si algún día una de estas tablas creciera hasta hacer esto caro, la
 * salida es una tabla de agregados, no volver a agrupar en SQL.
 */
@Repository
public class RepositorioVbstats {

    private final FuenteVbstats fuente;

    public RepositorioVbstats(FuenteVbstats fuente) {
        this.fuente = fuente;
    }

    private JdbcTemplate jdbc() {
        return fuente.jdbc();
    }

    // ---------------------------------------------------------------- usuarios

    public List<UsuarioApp> usuarios() {
        String sql = """
                SELECT u.id,
                       u.email,
                       u.name,
                       u.auth_provider,
                       u.subscription_type,
                       u.subscription_expires_at,
                       u.auto_renew,
                       u.created_at,
                       u.last_login_at,
                       u.is_superadmin,
                       u.stripe_customer_id,
                       u.apple_original_transaction_id,
                       (SELECT COUNT(*) FROM push_tokens pt WHERE pt.user_id = u.id) AS dispositivos,
                       (SELECT COUNT(*) FROM teams t WHERE t.user_id = u.id) AS equipos,
                       (SELECT COUNT(*) FROM matches m WHERE m.user_id = u.id) AS partidos
                  FROM users u
                 ORDER BY u.last_login_at DESC, u.created_at DESC
                """;

        return jdbc().query(sql, (rs, fila) -> aUsuario(rs));
    }

    private UsuarioApp aUsuario(ResultSet rs) throws SQLException {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("equipos", rs.getInt("equipos"));
        extra.put("partidos", rs.getInt("partidos"));
        extra.put("acceso", origen(rs.getString("auth_provider")));
        extra.put("renueva", rs.getBoolean("auto_renew"));
        extra.put("caduca", instante(rs.getTimestamp("subscription_expires_at")));
        extra.put("pasarela", pasarela(rs));
        extra.put("superadmin", rs.getBoolean("is_superadmin"));

        return new UsuarioApp(
                String.valueOf(rs.getInt("id")),
                rs.getString("name"),
                rs.getString("email"),
                instante(rs.getTimestamp("created_at")),
                instante(rs.getTimestamp("last_login_at")),
                rs.getString("subscription_type"),
                true,
                rs.getInt("dispositivos"),
                extra);
    }

    /** Por dónde paga, que no siempre es por dónde entra. */
    private String pasarela(ResultSet rs) throws SQLException {
        if (rs.getString("apple_original_transaction_id") != null) {
            return "Apple";
        }
        if (rs.getString("stripe_customer_id") != null) {
            return "Stripe";
        }
        return "—";
    }

    private String origen(String proveedor) {
        if (proveedor == null) {
            return "correo";
        }
        return "google".equalsIgnoreCase(proveedor) ? "Google" : "correo";
    }

    // ---------------------------------------------------------------- recuentos

    public int totalUsuarios() {
        Integer n = jdbc().queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return n == null ? 0 : n;
    }

    /** Cuántas cuentas han entrado desde un momento dado. */
    public int activosDesde(Instant desde) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM users WHERE last_login_at >= ?",
                Integer.class, Timestamp.from(desde));
        return n == null ? 0 : n;
    }

    public Map<String, Integer> usuariosPorPlan() {
        Map<String, Integer> porPlan = new LinkedHashMap<>();
        jdbc().query("SELECT subscription_type AS plan, COUNT(*) AS n FROM users GROUP BY subscription_type",
                rs -> {
                    String plan = rs.getString("plan");
                    porPlan.put(plan == null ? "free" : plan, rs.getInt("n"));
                });
        return porPlan;
    }

    public Map<String, Integer> dispositivosPorPlataforma() {
        Map<String, Integer> porPlataforma = new LinkedHashMap<>();
        // Las llaves no sobran: sin ellas la lambda devuelve el valor de `put` y el
        // compilador no sabe si es un RowCallbackHandler o un ResultSetExtractor.
        jdbc().query("SELECT platform, COUNT(*) AS n FROM push_tokens GROUP BY platform",
                rs -> {
                    porPlataforma.put(rs.getString("platform"), rs.getInt("n"));
                });
        return porPlataforma;
    }

    public int totalDispositivos() {
        Integer n = jdbc().queryForObject("SELECT COUNT(*) FROM push_tokens", Integer.class);
        return n == null ? 0 : n;
    }

    /** Cuentas con al menos un móvil registrado: el alcance real de un aviso. */
    public int usuariosAlcanzables() {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM push_tokens", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Las cuentas que compraron por la App Store.
     *
     * Son la puerta de entrada al dinero de Apple: la base de datos no guarda ni
     * un importe, solo este identificador, y con él se le pide a Apple el
     * historial de esa suscripción.
     */
    public List<ServicioAppStore.CuentaApple> cuentasConApple() {
        return jdbc().query("""
                SELECT id, email, apple_original_transaction_id
                  FROM users
                 WHERE apple_original_transaction_id IS NOT NULL
                   AND apple_original_transaction_id <> ''
                """, (rs, fila) -> new ServicioAppStore.CuentaApple(
                        rs.getInt("id"),
                        rs.getString("email"),
                        rs.getString("apple_original_transaction_id")));
    }

    public int totalPartidos() {
        Integer n = jdbc().queryForObject("SELECT COUNT(*) FROM matches", Integer.class);
        return n == null ? 0 : n;
    }

    /** Suscripciones de pago todavía vigentes. */
    public int suscripcionesActivas() {
        Integer n = jdbc().queryForObject("""
                SELECT COUNT(*) FROM users
                 WHERE subscription_type IN ('basic', 'pro')
                   AND (subscription_expires_at IS NULL OR subscription_expires_at > NOW())
                """, Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ series

    public Map<LocalDate, Double> altasPorDia(Rango rango) {
        return porDia(rango, "SELECT created_at AS momento FROM users WHERE created_at >= ? AND created_at < ?");
    }

    public Map<LocalDate, Double> partidosPorDia(Rango rango) {
        return porDia(rango, "SELECT created_at AS momento FROM matches WHERE created_at >= ? AND created_at < ?");
    }

    /**
     * Accesos por día... hasta donde se puede saber.
     *
     * VBStats guarda solo el ÚLTIMO acceso de cada cuenta, no un historial, así
     * que esto no es «cuánta gente entró cada día»: es «cuántas cuentas tienen su
     * último acceso ese día». Para los días recientes se parece bastante; hacia
     * atrás se queda corto, porque a quien entró el martes y volvió el viernes
     * solo se le cuenta el viernes. La gráfica lo dice en su etiqueta para que
     * nadie lo lea como lo que no es.
     *
     * Arreglarlo de verdad pide una tabla de accesos en VBStats. Cuando exista,
     * el cambio es esta consulta y nada más.
     */
    public Map<LocalDate, Double> accesosPorDia(Rango rango) {
        return porDia(rango, "SELECT last_login_at AS momento FROM users WHERE last_login_at >= ? AND last_login_at < ?");
    }

    private Map<LocalDate, Double> porDia(Rango rango, String sql) {
        List<Instant> momentos = jdbc().query(sql,
                (rs, fila) -> instante(rs.getTimestamp("momento")),
                Timestamp.from(rango.inicio()), Timestamp.from(rango.fin()));

        return momentos.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(rango::diaDe, Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (double) e.getValue()));
    }

    // ---------------------------------------------------------- notificaciones

    /** Los tokens a los que va dirigida una audiencia. */
    public List<String> tokensDe(String audiencia) {
        return switch (audiencia) {
            case "free", "basic", "pro" -> jdbc().queryForList("""
                    SELECT pt.token FROM push_tokens pt
                      JOIN users u ON pt.user_id = u.id
                     WHERE u.subscription_type = ?
                    """, String.class, audiencia);
            case "paid" -> jdbc().queryForList("""
                    SELECT pt.token FROM push_tokens pt
                      JOIN users u ON pt.user_id = u.id
                     WHERE u.subscription_type IN ('basic', 'pro')
                    """, String.class);
            default -> jdbc().queryForList("SELECT token FROM push_tokens", String.class);
        };
    }

    /** El historial de avisos, que en VBStats ya existe y aquí solo se lee. */
    public List<Map<String, Object>> historialNotificaciones(int limite) {
        return jdbc().queryForList("""
                SELECT n.id, n.title AS titulo, n.body AS cuerpo, n.sent_at AS enviado,
                       n.recipients_count AS destinatarios, n.audience AS audiencia,
                       u.email AS enviadoPor
                  FROM admin_notifications n
                  LEFT JOIN users u ON n.sent_by = u.id
                 ORDER BY n.sent_at DESC
                 LIMIT ?
                """, limite);
    }

    // ------------------------------------------------------------- escrituras
    // Todo lo que hay debajo de esta línea toca los datos de producción de
    // VBStats y va por el pool de escritura. Ver el aviso de FuenteVbstats.

    /**
     * Deja la notificación en el historial que la propia app enseña.
     *
     * {@code sent_by} es una clave ajena a {@code users}, así que hace falta un id
     * de VBStats: se busca por el correo del administrador del panel y, si esa
     * persona no tiene cuenta en la app, se usa el primer superadmin que haya. Si
     * no hubiera ninguno, se devuelve vacío y el aviso se manda igual sin quedar
     * registrado —perder la traza es malo, pero no mandar el aviso es peor.
     */
    public Integer registrarNotificacion(String titulo, String cuerpo, String audiencia, String emailAdmin) {
        Integer autor = idDe(emailAdmin);
        if (autor == null) {
            autor = primerSuperadmin();
        }
        if (autor == null) {
            return null;
        }

        var claves = new org.springframework.jdbc.support.GeneratedKeyHolder();
        Integer finalAutor = autor;
        fuente.jdbcEscritura().update(conexion -> {
            var ps = conexion.prepareStatement("""
                    INSERT INTO admin_notifications (title, body, sent_by, recipients_count, audience)
                    VALUES (?, ?, ?, 0, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, titulo);
            ps.setString(2, cuerpo);
            ps.setInt(3, finalAutor);
            ps.setString(4, audiencia);
            return ps;
        }, claves);

        Number id = claves.getKey();
        return id == null ? null : id.intValue();
    }

    /** Apunta a cuántos llegó de verdad, una vez que FCM ha contestado. */
    public void ajustarDestinatarios(int notificacionId, int destinatarios) {
        fuente.jdbcEscritura().update(
                "UPDATE admin_notifications SET recipients_count = ? WHERE id = ?",
                destinatarios, notificacionId);
    }

    /**
     * Borra los tokens que FCM ha dado por muertos.
     *
     * Es limpieza, no un borrado de datos de nadie: un token caducado es un móvil
     * que desinstaló la app o al que se le revocó el permiso. Dejarlos ahí infla
     * el recuento de «dispositivos alcanzables» y hace que cada envío gaste
     * intentos en direcciones que ya no existen.
     */
    public int limpiarTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        int borrados = 0;
        for (String token : tokens) {
            borrados += fuente.jdbcEscritura().update("DELETE FROM push_tokens WHERE token = ?", token);
        }
        return borrados;
    }

    /**
     * Cambia el plan de una cuenta a mano.
     *
     * Solo toca `subscription_type`. NO se tocan `stripe_customer_id`,
     * `apple_original_transaction_id` ni `subscription_expires_at`, y esto es
     * deliberado: son el registro de lo que esa persona pagó de verdad. Borrarlos
     * para «dejarlo limpio» destruiría la única prueba de una compra y dejaría
     * descuadrado el panel de ingresos.
     *
     * Sí se limpia la caducidad cuando se pasa a `free`, porque una fecha de fin
     * de suscripción en una cuenta gratuita no significa nada y confunde al leer
     * la ficha.
     */
    public void cambiarPlan(int id, String plan) {
        if ("free".equals(plan)) {
            fuente.jdbcEscritura().update(
                    "UPDATE users SET subscription_type = ?, subscription_expires_at = NULL WHERE id = ?",
                    plan, id);
            return;
        }
        fuente.jdbcEscritura().update(
                "UPDATE users SET subscription_type = ? WHERE id = ?", plan, id);
    }

    /** El plan actual y por dónde paga, para decidir si avisar y para la auditoría. */
    public Map<String, Object> planDe(int id) {
        List<Map<String, Object>> filas = jdbc().queryForList("""
                SELECT id, email, subscription_type,
                       stripe_subscription_id IS NOT NULL AS tieneStripe,
                       apple_original_transaction_id IS NOT NULL AS tieneApple
                  FROM users WHERE id = ?
                """, id);
        return filas.isEmpty() ? Map.of() : filas.get(0);
    }

    public Integer idDe(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        List<Integer> ids = jdbc().queryForList(
                "SELECT id FROM users WHERE email = ? LIMIT 1", Integer.class, email);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Integer primerSuperadmin() {
        List<Integer> ids = jdbc().queryForList(
                "SELECT id FROM users WHERE is_superadmin = 1 ORDER BY id LIMIT 1", Integer.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Datos mínimos de una cuenta, para poder decidir si se puede borrar. */
    public Map<String, Object> resumenUsuario(int id) {
        List<Map<String, Object>> filas = jdbc().queryForList(
                "SELECT id, email, is_superadmin FROM users WHERE id = ?", id);
        return filas.isEmpty() ? Map.of() : filas.get(0);
    }

    /**
     * Borra una cuenta y todo lo suyo.
     *
     * Va en una transacción y en el mismo orden que usa el backend de VBStats en
     * su propio panel, que no es caprichoso: {@code stat_settings} no tiene borrado
     * en cascada, y los jugadores cuelgan del equipo con SET NULL, así que si se
     * borra al usuario primero quedan filas huérfanas que ya nadie sabe de quién
     * eran.
     */
    public void borrarUsuario(int id) {
        var plantilla = fuente.jdbcEscritura();
        var transacciones = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                java.util.Objects.requireNonNull(plantilla.getDataSource()));
        new org.springframework.transaction.support.TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            plantilla.update("DELETE FROM stat_settings WHERE user_id = ?", id);
            plantilla.update(
                    "DELETE FROM players WHERE team_id IN (SELECT id FROM teams WHERE user_id = ?)", id);
            plantilla.update("DELETE FROM users WHERE id = ?", id);
        });
    }

    private Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
