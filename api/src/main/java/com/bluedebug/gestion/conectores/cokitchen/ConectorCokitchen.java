package com.bluedebug.gestion.conectores.cokitchen;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.EdicionRol;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import com.bluedebug.gestion.conectores.modelo.Metrica;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Reparto;
import com.bluedebug.gestion.conectores.modelo.ResultadoAccion;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import com.bluedebug.gestion.conectores.modelo.Serie;
import com.bluedebug.gestion.conectores.modelo.Tabla;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Co-Kitchen: la despensa compartida.
 *
 * Es la tercera app del panel y la primera que vive en Supabase, así que se
 * parece a VBStats por fuera —SQL, cuentas, planes— y no por dentro: los datos de
 * una cuenta están partidos entre {@code auth.users} (de Supabase) y
 * {@code public.profiles} (de la app), y lo que hay debajo no son partidos de un
 * usuario sino espacios COMPARTIDOS por varios. Eso cambia lo único que de verdad
 * cambia: borrar a alguien no es borrar sus cosas, porque sus cosas son también
 * de sus compañeros de piso. Ver {@link RepositorioCokitchen#borrarUsuario}.
 *
 * Aquí todavía no hay dinero. El PRO se dará desde este panel en cuanto exista la
 * columna que lo guarde, y el conector se entera solo: ver
 * {@link RepositorioCokitchen#hayPlan()}.
 */
@Component
public class ConectorCokitchen implements ConectorApp {

    private static final Logger log = LoggerFactory.getLogger(ConectorCokitchen.class);

    /** Cuánto tiene que hacer que alguien no entra para no contarlo como activo. */
    private static final int DIAS_ACTIVIDAD = 30;

    private final FuenteCokitchen fuente;
    private final RepositorioCokitchen repositorio;

    public ConectorCokitchen(FuenteCokitchen fuente, RepositorioCokitchen repositorio) {
        this.fuente = fuente;
        this.repositorio = repositorio;
    }

    @Override
    public DescriptorApp descriptor() {
        List<DescriptorApp.Capacidad> capacidades = new ArrayList<>(List.of(
                DescriptorApp.Capacidad.USUARIOS,
                DescriptorApp.Capacidad.METRICAS,
                DescriptorApp.Capacidad.BORRAR_USUARIOS));

        // El selector de plan solo se enseña cuando hay dónde guardarlo. Un
        // desplegable que falla al pulsarlo es peor que no tener desplegable.
        //
        // Aquí se pregunta por `configurado` y no por `estado()`: el descriptor
        // se pide en cada pintada del menú y de la pantalla, y `estado()` hace un
        // viaje a la base de datos. `hayPlan()` ya trae su propia caché y devuelve
        // false si la base no contesta, que es lo que hay que enseñar de todos
        // modos.
        if (fuente.configurado() && repositorio.hayPlan()) {
            capacidades.add(DescriptorApp.Capacidad.EDITAR_ROL);
        }

        return new DescriptorApp(
                "cokitchen",
                "Co-Kitchen",
                "Despensa compartida con voz y tickets",
                "#D97757",
                "nevera",
                List.of("ios", "android"),
                capacidades,
                List.of(
                        new DescriptorApp.CampoExtra("espacios", "Espacios"),
                        new DescriptorApp.CampoExtra("acceso", "Entra con"),
                        new DescriptorApp.CampoExtra("idioma", "Idioma"),
                        new DescriptorApp.CampoExtra("avisos", "Avisos")));
    }

    @Override
    public EstadoConector estado() {
        if (!fuente.configurado()) {
            return EstadoConector.sinConfigurar(
                    "Falta BLUEDEBUG_COKITCHEN_URL con la conexión a la base de datos de Supabase "
                            + "de Co-Kitchen.");
        }
        if (!fuente.disponible()) {
            return EstadoConector.sinConfigurar(
                    "La base de datos de Co-Kitchen no responde. Mira si el proyecto de Supabase "
                            + "sigue despierto y si la contraseña de la base sigue siendo esa.");
        }
        return EstadoConector.listo();
    }

    @Override
    public ResumenApp resumen(Rango rango) {
        EstadoConector estado = estado();
        if (!estado.disponible()) {
            return ResumenApp.noDisponible(descriptor(), estado);
        }

        int total = repositorio.totalUsuarios();
        int activos = repositorio.activosDesde(Rango.ultimosDias(DIAS_ACTIVIDAD).inicio());
        int sinEspacio = repositorio.cuentasSinEspacio();

        var altas = repositorio.altasPorDia(rango);
        var altasAntes = repositorio.altasPorDia(rango.anterior());
        double altasAhora = altas.values().stream().mapToDouble(Double::doubleValue).sum();
        double altasAntesTotal = altasAntes.values().stream().mapToDouble(Double::doubleValue).sum();

        List<Metrica> metricas = new ArrayList<>();
        metricas.add(Metrica.entero("usuarios", "Cuentas", total,
                total == 0 ? "todavía sin usuarios" : "desde el primer registro"));
        metricas.add(Metrica.entero("altas", "Altas en el periodo", altasAhora,
                "en los últimos " + rango.dias() + " días")
                .variando(variacion(altasAhora, altasAntesTotal)));
        metricas.add(Metrica.entero("activos", "Activos", activos,
                "han entrado en " + DIAS_ACTIVIDAD + " días"));
        metricas.add(Metrica.entero("espacios", "Espacios", repositorio.totalEspacios(),
                "cocinas compartidas"));
        metricas.add(Metrica.entero("productos", "Productos en despensa", repositorio.totalProductos(),
                repositorio.totalTickets() + " tickets escaneados"));
        metricas.add(Metrica.entero("dispositivos", "Móviles con avisos", repositorio.totalDispositivos(),
                repositorio.usuariosAlcanzables() + " cuentas alcanzables"));
        // La cifra que dice cuánta gente se cae en el primer paso: se registró y
        // no llegó a montar ni a entrar en una cocina.
        metricas.add(Metrica.entero("sinespacio", "Registrados sin espacio", sinEspacio,
                total == 0 ? "" : porcentajeTexto(sinEspacio, total) + " de las cuentas"));

        List<Serie> series = List.of(
                rango.rellenar("altas", "Altas", "entero", altas),
                rango.rellenar("movimientos", "Movimientos de despensa", "entero",
                        repositorio.movimientosPorDia(rango)),
                rango.rellenar("accesos", "Cuentas con su último acceso ese día", "entero",
                        repositorio.accesosPorDia(rango)));

        List<Reparto> repartos = new ArrayList<>();
        if (repositorio.hayPlan()) {
            Map<String, Integer> porPlan = repositorio.usuariosPorPlan();
            repartos.add(new Reparto("planes", "Cuentas por plan", List.of(
                    new Reparto.Trozo("Gratis", porPlan.getOrDefault("free", 0), "#64748B"),
                    new Reparto.Trozo("PRO", porPlan.getOrDefault("pro", 0), "#D97757"))));
        }
        repartos.add(new Reparto("plataformas", "Dispositivos por plataforma",
                repositorio.dispositivosPorPlataforma().entrySet().stream()
                        .map(e -> Reparto.Trozo.de(etiquetaPlataforma(e.getKey()), e.getValue()))
                        .toList()));

        return new ResumenApp(descriptor(), estado, metricas, series, repartos);
    }

    @Override
    public List<UsuarioApp> usuarios() {
        return estado().disponible() ? repositorio.usuarios() : List.of();
    }

    @Override
    public List<Tabla> tablas() {
        if (!estado().disponible()) {
            return List.of();
        }
        return List.of(new Tabla(
                "espacios",
                "Espacios",
                List.of(
                        Tabla.Columna.texto("espacio", "Espacio"),
                        Tabla.Columna.entero("miembros", "Miembros"),
                        Tabla.Columna.entero("productos", "Productos"),
                        Tabla.Columna.fecha("creado", "Creado")),
                repositorio.espacios(25),
                "Todavía no hay ningún espacio"));
    }

    // ------------------------------------------------------------- el plan PRO

    @Override
    public Optional<EdicionRol> edicionRol() {
        if (!fuente.configurado() || !repositorio.hayPlan()) {
            return Optional.empty();
        }
        return Optional.of(new EdicionRol(
                "Plan",
                false,
                List.of(
                        EdicionRol.Opcion.de("free", "Gratis"),
                        EdicionRol.Opcion.de("pro", "PRO")),
                "Esto escribe el plan directamente en la base de datos de Co-Kitchen. No cobra "
                        + "nada ni cancela nada en ninguna tienda: sirve para regalar el PRO y para "
                        + "quitarlo. Si algún día Co-Kitchen cobra por Google Play o por la App "
                        + "Store, lo que diga la tienda mandará sobre esto en cuanto la app "
                        + "resincronice."));
    }

    @Override
    public ResultadoAccion cambiarRol(String usuarioId, List<String> roles, String emailAdmin) {
        if (!repositorio.hayPlan()) {
            return ResultadoAccion.error(
                    "Co-Kitchen todavía no tiene planes: falta la columna `plan` en `public.profiles`. "
                            + "Se crea con: alter table public.profiles add column plan text not null "
                            + "default 'free' check (plan in ('free','pro')); en cuanto exista, este "
                            + "selector aparece solo.");
        }

        UUID id = comoUuid(usuarioId);

        // Un solo valor: Co-Kitchen declara `multiple = false`.
        if (roles == null || roles.size() != 1) {
            throw new PeticionInvalida("Hay que indicar exactamente un plan");
        }
        String plan = roles.get(0);
        if (!edicionRol().orElseThrow().admite(plan)) {
            throw new PeticionInvalida("Ese plan no existe en Co-Kitchen");
        }

        Map<String, Object> cuenta = repositorio.resumenUsuario(id);
        if (cuenta.isEmpty()) {
            throw new PeticionInvalida("Esa cuenta no existe");
        }

        String guardado = repositorio.planDe(id);
        String anterior = guardado == null ? "free" : guardado;
        if (plan.equals(anterior)) {
            return ResultadoAccion.ok("Ya estaba en " + plan);
        }

        if (repositorio.cambiarPlan(id, plan) == 0) {
            return ResultadoAccion.error(
                    "Esa cuenta existe en Supabase pero no tiene perfil en `public.profiles`, "
                            + "así que no hay dónde guardarle el plan.");
        }

        log.warn("AUDITORÍA: {} cambió el plan de {} ({}) de {} a {} en Co-Kitchen",
                emailAdmin, id, cuenta.get("email"), anterior, plan);

        return ResultadoAccion.ok("Plan cambiado de " + anterior + " a " + plan);
    }

    // ----------------------------------------------------------------- borrado

    @Override
    public ResultadoAccion borrarUsuario(String usuarioId, String emailAdmin) {
        UUID id = comoUuid(usuarioId);

        Map<String, Object> cuenta = repositorio.resumenUsuario(id);
        if (cuenta.isEmpty()) {
            throw new PeticionInvalida("Esa cuenta no existe");
        }

        long propios = numero(cuenta.get("espaciosPropios"));
        repositorio.borrarUsuario(id);

        log.warn("AUDITORÍA: {} borró la cuenta {} ({}) de Co-Kitchen, dueña de {} espacios",
                emailAdmin, id, cuenta.get("email"), propios);

        // Se dice lo que ha pasado con los espacios porque es lo que el panel no
        // puede deshacer: quien borra una cuenta tiene que saber si acaba de
        // cambiar de dueño la cocina de otras tres personas.
        String base = "Cuenta " + cuenta.get("email") + " borrada";
        if (propios == 0) {
            return ResultadoAccion.ok(base + " con su perfil y sus móviles");
        }
        return ResultadoAccion.ok(base + ". Era dueña de " + propios
                + (propios == 1 ? " espacio" : " espacios")
                + ": los que tenían más gente han pasado al miembro más antiguo y los que estaban "
                + "vacíos se han borrado con su despensa.");
    }

    // ------------------------------------------------------------------- apoyo

    /**
     * Los identificadores de Supabase son UUID de verdad, no texto.
     *
     * Se convierte aquí y no se deja pasar la cadena: Postgres rechazaría un
     * texto contra una columna {@code uuid} con un error de tipos que no dice
     * nada, y así el que sale es el que se entiende.
     */
    private UUID comoUuid(String usuarioId) {
        try {
            return UUID.fromString(usuarioId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PeticionInvalida("El id de usuario de Co-Kitchen es un UUID");
        }
    }

    private long numero(Object valor) {
        return valor instanceof Number n ? n.longValue() : 0L;
    }

    private Double variacion(double ahora, double antes) {
        if (antes == 0) {
            return null;
        }
        return (ahora - antes) / antes;
    }

    private String porcentajeTexto(int parte, int total) {
        if (total == 0) {
            return "0 %";
        }
        return Math.round(parte * 100d / total) + " %";
    }

    private String etiquetaPlataforma(String plataforma) {
        if (plataforma == null) {
            return "Sin identificar";
        }
        return switch (plataforma) {
            case "ios" -> "iPhone";
            case "android" -> "Android";
            default -> "Sin identificar";
        };
    }
}
