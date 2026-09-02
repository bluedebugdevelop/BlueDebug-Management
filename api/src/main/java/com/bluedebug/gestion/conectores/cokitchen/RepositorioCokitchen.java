package com.bluedebug.gestion.conectores.cokitchen;

import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Las consultas contra la base de datos de Co-Kitchen.
 *
 * DÓNDE ESTÁ CADA COSA. Co-Kitchen es una app de Supabase, y eso parte los datos
 * de una cuenta en dos sitios: el correo, el alta y el último acceso están en
 * {@code auth.users}, que es de Supabase y no se toca; el nombre visible, el
 * idioma y las preferencias de aviso están en {@code public.profiles}, que sí es
 * de la app. Todo lo de aquí sale de juntar las dos por el id.
 *
 * Sobre agrupar por día: igual que en VBStats, se traen los instantes en crudo y
 * se agrupan en Java. Postgres sí sabe de husos y podría hacerlo, pero entonces
 * cada consulta tendría que acordarse de la zona, y basta con que una se olvide
 * para que su gráfica quede desplazada respecto a las de al lado sin que se note.
 * La conversión se hace en un solo sitio ({@link Rango#diaDe}).
 */
@Repository
public class RepositorioCokitchen {

    private static final Logger log = LoggerFactory.getLogger(RepositorioCokitchen.class);

    /**
     * Cada cuánto se vuelve a mirar si ya existe la columna del plan.
     *
     * Ver {@link #hayPlan()}: la columna aparecerá un día sin que nadie reinicie
     * el panel, así que la respuesta caduca. Un minuto es lo mismo que dura la
     * caché de los resúmenes.
     */
    private static final long VIGENCIA_PLAN_MS = 60_000;

    private final FuenteCokitchen fuente;

    private volatile boolean hayPlan;
    private volatile long planComprobadoEn;

    public RepositorioCokitchen(FuenteCokitchen fuente) {
        this.fuente = fuente;
    }

    private JdbcTemplate jdbc() {
        return fuente.jdbc();
    }

    // -------------------------------------------------------------- el plan PRO

    /**
     * Si Co-Kitchen ya tiene planes.
     *
     * El día que se escribió esto, Co-Kitchen no cobraba: todas las cuentas son
     * iguales y no hay ninguna columna que diga lo contrario. El PRO llegará, y
     * cuando llegue no puede hacer falta tocar el panel: se pregunta al catálogo
     * si {@code public.profiles} tiene una columna {@code plan}, y el día que la
     * tenga aparecen solos el selector de plan de la tabla de usuarios y el
     * reparto de cuentas por plan.
     *
     * El contrato, para cuando se cree, es de una línea:
     *
     * <pre>
     * alter table public.profiles
     *   add column plan text not null default 'free'
     *   check (plan in ('free', 'pro'));
     * </pre>
     *
     * Cualquier otro valor en esa columna se lee igual, pero el panel solo sabe
     * poner esos dos.
     */
    public boolean hayPlan() {
        if (!fuente.configurado()) {
            return false;
        }
        long ahora = System.currentTimeMillis();
        if (ahora - planComprobadoEn < VIGENCIA_PLAN_MS) {
            return hayPlan;
        }
        try {
            Integer n = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'profiles'
                       AND column_name = 'plan'
                    """, Integer.class);
            hayPlan = n != null && n > 0;
        } catch (Exception e) {
            // Se traga la excepción a propósito —una app caída no puede tumbar el
            // panel— pero no en silencio: sin esta línea, una base que no responde
            // y una columna que todavía no existe se ven exactamente igual desde
            // fuera, y son dos problemas distintos.
            log.warn("Co-Kitchen: no se pudo comprobar si `profiles` tiene columna `plan`, "
                    + "se sigue como si no la tuviera: {}", e.getMessage());
            hayPlan = false;
        }
        planComprobadoEn = ahora;
        return hayPlan;
    }

    // ---------------------------------------------------------------- usuarios

    public List<UsuarioApp> usuarios() {
        // La columna del plan se interpola porque puede no existir todavía, y una
        // consulta que la nombra sin que exista no devuelve nulos: falla entera.
        // No hay dato de nadie en esa interpolación, solo la decisión de arriba.
        String plan = hayPlan() ? "p.plan" : "'free'";

        String sql = """
                SELECT u.id,
                       u.email,
                       u.created_at,
                       u.last_sign_in_at,
                       u.banned_until,
                       u.raw_app_meta_data ->> 'provider' AS proveedor,
                       p.display_name,
                       p.language,
                       p.notify_expiry_enabled,
                       p.marketing_opt_in,
                       %s AS plan,
                       (SELECT COUNT(*) FROM public.group_members gm WHERE gm.user_id = u.id) AS espacios,
                       (SELECT COUNT(*) FROM public.device_tokens dt WHERE dt.user_id = u.id) AS dispositivos
                  FROM auth.users u
                  LEFT JOIN public.profiles p ON p.id = u.id
                 WHERE u.deleted_at IS NULL
                 ORDER BY u.last_sign_in_at DESC NULLS LAST, u.created_at DESC
                """.formatted(plan);

        return jdbc().query(sql, (rs, fila) -> aUsuario(rs));
    }

    private UsuarioApp aUsuario(ResultSet rs) throws SQLException {
        Instant bloqueadoHasta = instante(rs.getTimestamp("banned_until"));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("espacios", rs.getInt("espacios"));
        extra.put("acceso", origen(rs.getString("proveedor")));
        extra.put("idioma", rs.getString("language"));
        extra.put("avisos", rs.getBoolean("notify_expiry_enabled"));
        extra.put("marketing", rs.getBoolean("marketing_opt_in"));

        return new UsuarioApp(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("email"),
                instante(rs.getTimestamp("created_at")),
                instante(rs.getTimestamp("last_sign_in_at")),
                rs.getString("plan"),
                // Supabase no borra al bloquear a alguien: le pone fecha de fin de
                // castigo. Una fecha ya pasada no bloquea nada.
                bloqueadoHasta == null || bloqueadoHasta.isBefore(Instant.now()),
                rs.getInt("dispositivos"),
                extra);
    }

    /** Por dónde entra. Supabase lo guarda en los metadatos del proveedor. */
    private String origen(String proveedor) {
        if (proveedor == null) {
            return "correo";
        }
        return "google".equalsIgnoreCase(proveedor) ? "Google" : "correo";
    }

    // --------------------------------------------------------------- recuentos

    public int totalUsuarios() {
        return contar("SELECT COUNT(*) FROM auth.users WHERE deleted_at IS NULL");
    }

    /** Cuántas cuentas han entrado desde un momento dado. */
    public int activosDesde(Instant desde) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM auth.users WHERE deleted_at IS NULL AND last_sign_in_at >= ?",
                Integer.class, Timestamp.from(desde));
        return n == null ? 0 : n;
    }

    public int totalEspacios() {
        return contar("SELECT COUNT(*) FROM public.groups");
    }

    public int totalProductos() {
        return contar("SELECT COUNT(*) FROM public.inventory_items");
    }

    public int totalTickets() {
        return contar("SELECT COUNT(*) FROM public.receipts");
    }

    public int totalDispositivos() {
        return contar("SELECT COUNT(*) FROM public.device_tokens");
    }

    /** Cuentas con al menos un móvil registrado: el alcance real de un aviso. */
    public int usuariosAlcanzables() {
        return contar("SELECT COUNT(DISTINCT user_id) FROM public.device_tokens");
    }

    /**
     * Cuentas sin ningún espacio.
     *
     * En Co-Kitchen todo cuelga de un espacio: quien no tiene ninguno se registró
     * y no llegó a empezar. Es la cifra que dice cuánta gente se cae en el primer
     * paso, y por eso va arriba y no escondida en la tabla.
     */
    public int cuentasSinEspacio() {
        return contar("""
                SELECT COUNT(*) FROM auth.users u
                 WHERE u.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM public.group_members gm WHERE gm.user_id = u.id)
                """);
    }

    public Map<String, Integer> usuariosPorPlan() {
        if (!hayPlan()) {
            return Map.of();
        }
        Map<String, Integer> porPlan = new LinkedHashMap<>();
        jdbc().query("SELECT COALESCE(plan, 'free') AS plan, COUNT(*) AS n FROM public.profiles GROUP BY 1",
                rs -> {
                    porPlan.put(rs.getString("plan"), rs.getInt("n"));
                });
        return porPlan;
    }

    public Map<String, Integer> dispositivosPorPlataforma() {
        Map<String, Integer> porPlataforma = new LinkedHashMap<>();
        // Las llaves no sobran: sin ellas la lambda devuelve el valor de `put` y
        // el compilador no sabe si es un RowCallbackHandler o un ResultSetExtractor.
        jdbc().query("SELECT platform, COUNT(*) AS n FROM public.device_tokens GROUP BY platform",
                rs -> {
                    porPlataforma.put(rs.getString("platform"), rs.getInt("n"));
                });
        return porPlataforma;
    }

    private int contar(String sql) {
        Integer n = jdbc().queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ series

    public Map<LocalDate, Double> altasPorDia(Rango rango) {
        return porDia(rango, """
                SELECT created_at AS momento FROM auth.users
                 WHERE deleted_at IS NULL AND created_at >= ? AND created_at < ?
                """);
    }

    /**
     * Accesos por día... hasta donde se puede saber.
     *
     * Supabase guarda solo el ÚLTIMO acceso de cada cuenta, no un historial, así
     * que esto no es «cuánta gente entró cada día»: es «cuántas cuentas tienen su
     * último acceso ese día». Para los días recientes se parece bastante; hacia
     * atrás se queda corto, porque a quien entró el martes y volvió el viernes
     * solo se le cuenta el viernes. La gráfica lo dice en su etiqueta para que
     * nadie lo lea como lo que no es.
     */
    public Map<LocalDate, Double> accesosPorDia(Rango rango) {
        return porDia(rango, """
                SELECT last_sign_in_at AS momento FROM auth.users
                 WHERE deleted_at IS NULL AND last_sign_in_at >= ? AND last_sign_in_at < ?
                """);
    }

    /**
     * Movimientos de despensa por día: lo que se mete y lo que se gasta.
     *
     * Es la señal de uso REAL de Co-Kitchen. Entrar en la app no significa gran
     * cosa —se abre para mirar la lista de la compra— pero apuntar un consumo sí:
     * es alguien usándola para lo que es.
     */
    public Map<LocalDate, Double> movimientosPorDia(Rango rango) {
        return porDia(rango, """
                SELECT created_at AS momento FROM public.consumption_log
                 WHERE created_at >= ? AND created_at < ?
                """);
    }

    private Map<LocalDate, Double> porDia(Rango rango, String sql) {
        List<Instant> momentos = jdbc().query(sql,
                (rs, fila) -> instante(rs.getTimestamp("momento")),
                Timestamp.from(rango.inicio()), Timestamp.from(rango.fin()));

        return momentos.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(rango::diaDe, Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (double) e.getValue()));
    }

    // ------------------------------------------------------------------ tablas

    /** Los espacios: quién los comparte y cuánto tienen dentro. */
    public List<Map<String, Object>> espacios(int limite) {
        return jdbc().queryForList("""
                SELECT g.name AS espacio,
                       (SELECT COUNT(*) FROM public.group_members gm WHERE gm.group_id = g.id) AS miembros,
                       (SELECT COUNT(*) FROM public.inventory_items i WHERE i.group_id = g.id) AS productos,
                       g.created_at AS creado
                  FROM public.groups g
                 ORDER BY g.created_at DESC
                 LIMIT ?
                """, limite);
    }

    // ------------------------------------------------------------------ el plan

    /** El plan actual de una cuenta, para la auditoría y para no escribir de más. */
    public String planDe(UUID id) {
        if (!hayPlan()) {
            return null;
        }
        List<String> planes = jdbc().queryForList(
                "SELECT COALESCE(plan, 'free') FROM public.profiles WHERE id = ?", String.class, id);
        return planes.isEmpty() ? null : planes.get(0);
    }

    /**
     * Escribe el plan y dice si escribió algo.
     *
     * Devuelve las filas tocadas y no {@code void} porque el perfil puede no
     * existir: lo crea un disparador al registrarse, pero una cuenta creada a
     * mano en Supabase no pasa por ahí. Sin este dato, el panel diría «plan
     * cambiado» sobre una fila que no ha tocado.
     */
    public int cambiarPlan(UUID id, String plan) {
        return fuente.jdbcEscritura().update("UPDATE public.profiles SET plan = ? WHERE id = ?", plan, id);
    }

    // ----------------------------------------------------------------- borrado

    /**
     * Datos mínimos de una cuenta, para poder decidir si se puede borrar.
     *
     * El alias va entre comillas porque Postgres, a diferencia de MySQL, pasa a
     * minúsculas todo identificador que no las lleve: sin ellas la columna
     * vuelve como {@code espaciospropios} y la clave que se busca luego no
     * existe, sin error y sin ruido.
     */
    public Map<String, Object> resumenUsuario(UUID id) {
        List<Map<String, Object>> filas = jdbc().queryForList("""
                SELECT u.id, u.email, p.display_name,
                       (SELECT COUNT(*) FROM public.group_members gm
                         WHERE gm.user_id = u.id AND gm.role = 'owner') AS "espaciosPropios"
                  FROM auth.users u
                  LEFT JOIN public.profiles p ON p.id = u.id
                 WHERE u.id = ? AND u.deleted_at IS NULL
                """, id);
        return filas.isEmpty() ? Map.of() : filas.get(0);
    }

    /**
     * Borra una cuenta con lo mismo que hace la propia app.
     *
     * Co-Kitchen ya sabe borrar una cuenta: tiene la función {@code delete_my_account()},
     * la que exige Google Play desde 2024. Pero esa función borra a QUIEN LLAMA
     * ({@code auth.uid()}), y el panel no entra como el usuario, así que no se
     * puede reutilizar tal cual: lo que se reutiliza es su guion, paso por paso y
     * en el mismo orden.
     *
     * Y el orden es lo que importa aquí, porque un espacio de Co-Kitchen es
     * COMPARTIDO. Borrar sin más a quien creó el piso se llevaría por delante la
     * despensa de sus compañeros, que no han pedido nada:
     *
     *   1. De cada espacio del que es dueño: si queda alguien más, el mando pasa
     *      al miembro más antiguo; si estaba solo, el espacio se borra entero.
     *   2. Los espacios que sobreviven y que además CREÓ él pasan a figurar como
     *      creados por su nuevo dueño. Ver el bloque de abajo: sin este paso el
     *      borrado revienta.
     *   3. Lo que hizo en espacios ajenos se queda, pero sin nombre: el historial
     *      del grupo sigue cuadrando y deja de apuntar a una persona.
     *   4. Y entonces sí, fuera de {@code auth.users}, que en cascada se lleva su
     *      perfil, sus pertenencias y sus móviles.
     *
     * Todo en una transacción: a medias quedaría un espacio sin dueño, que es un
     * espacio en el que ya nadie puede invitar ni echar a nadie.
     *
     * EL PASO 2 NO SOBRA, AUNQUE LA APP NO LO HAGA. En Co-Kitchen,
     * {@code groups.created_by} es {@code NOT NULL} y su clave ajena es
     * {@code ON DELETE SET NULL}: las dos cosas a la vez no pueden cumplirse. En
     * cuanto se borre a alguien que creó un espacio que le sobrevive, Postgres
     * intenta poner ese campo a nulo y aborta con «null value violates not-null
     * constraint». Hoy no se ha dado —ningún espacio ha cambiado de dueño
     * todavía— pero se dará en cuanto alguien ceda el mando o se vaya el que
     * montó el piso. La propia app arrastra el mismo fallo en
     * {@code delete_my_account()}, y ahí es peor, porque es el borrado de cuenta
     * que exige Google Play. Aquí se esquiva reasignando el creador; arreglarlo
     * de raíz es quitarle el {@code NOT NULL} a esa columna en Co-Kitchen.
     */
    public void borrarUsuario(UUID id) {
        JdbcTemplate plantilla = fuente.jdbcEscritura();
        var transacciones = new DataSourceTransactionManager(
                Objects.requireNonNull(plantilla.getDataSource()));

        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            List<UUID> propios = plantilla.queryForList(
                    "SELECT group_id FROM public.group_members WHERE user_id = ? AND role = 'owner'",
                    UUID.class, id);

            for (UUID espacio : propios) {
                Integer otros = plantilla.queryForObject(
                        "SELECT COUNT(*) FROM public.group_members WHERE group_id = ? AND user_id <> ?",
                        Integer.class, espacio, id);

                if (otros != null && otros > 0) {
                    plantilla.update("""
                            UPDATE public.group_members SET role = 'owner'
                             WHERE group_id = ?
                               AND user_id = (SELECT user_id FROM public.group_members
                                               WHERE group_id = ? AND user_id <> ?
                                               ORDER BY joined_at LIMIT 1)
                            """, espacio, espacio, id);
                } else {
                    plantilla.update("DELETE FROM public.groups WHERE id = ?", espacio);
                }
            }

            // El creador de los espacios que sobreviven pasa a ser su dueño de
            // ahora. Se excluye al que se va porque su fila de miembro todavía
            // existe —el traspaso de arriba asciende al nuevo dueño pero no
            // degrada al viejo— y elegirlo a él dejaría el campo apuntando al
            // que estamos borrando.
            plantilla.update("""
                    UPDATE public.groups g
                       SET created_by = (SELECT gm.user_id FROM public.group_members gm
                                          WHERE gm.group_id = g.id
                                            AND gm.role = 'owner'
                                            AND gm.user_id <> ?
                                          ORDER BY gm.joined_at LIMIT 1)
                     WHERE g.created_by = ?
                       AND EXISTS (SELECT 1 FROM public.group_members gm
                                    WHERE gm.group_id = g.id
                                      AND gm.role = 'owner'
                                      AND gm.user_id <> ?)
                    """, id, id, id);

            plantilla.update("UPDATE public.consumption_log SET consumed_by = NULL WHERE consumed_by = ?", id);
            plantilla.update("""
                    UPDATE public.inventory_items SET added_by = NULL, last_updated_by = NULL
                     WHERE added_by = ? OR last_updated_by = ?
                    """, id, id);
            plantilla.update("""
                    UPDATE public.shopping_list_items SET added_by = NULL, checked_by = NULL
                     WHERE added_by = ? OR checked_by = ?
                    """, id, id);
            plantilla.update("UPDATE public.receipts SET uploaded_by = NULL WHERE uploaded_by = ?", id);
            plantilla.update("UPDATE public.pending_questions SET user_id = NULL WHERE user_id = ?", id);

            plantilla.update("DELETE FROM auth.users WHERE id = ?", id);
        });
    }

    private Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
