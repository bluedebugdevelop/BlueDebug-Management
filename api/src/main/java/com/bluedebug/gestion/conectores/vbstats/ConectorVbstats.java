package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.modelo.AccionAdmin;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import com.bluedebug.gestion.conectores.modelo.Ingresos;
import com.bluedebug.gestion.conectores.modelo.Metrica;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Reparto;
import com.bluedebug.gestion.conectores.modelo.ResultadoAccion;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import com.bluedebug.gestion.conectores.modelo.Serie;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * VBStats: la app de estadísticas de voleibol.
 *
 * Junta tres fuentes que no se hablan entre ellas:
 *   · MySQL   — las cuentas, los equipos, los partidos y los tokens de aviso.
 *   · Stripe  — el dinero (ver la advertencia sobre Apple en {@link ServicioStripe}).
 *   · FCM     — por donde salen las notificaciones.
 *
 * Cada una puede faltar por su cuenta, y el conector lo aguanta: sin Stripe se
 * esconde la pestaña de ingresos, sin Firebase el botón de notificar avisa de que
 * no hay credenciales, y sin MySQL la app entera sale apagada en el menú.
 */
@Component
public class ConectorVbstats implements ConectorApp {

    private static final Logger log = LoggerFactory.getLogger(ConectorVbstats.class);

    /** Cuánto tiene que hacer que alguien no entra para no contarlo como activo. */
    private static final int DIAS_ACTIVIDAD = 30;

    private final FuenteVbstats fuente;
    private final RepositorioVbstats repositorio;
    private final ServicioStripe stripe;
    private final ServicioFcm fcm;

    public ConectorVbstats(FuenteVbstats fuente,
                           RepositorioVbstats repositorio,
                           ServicioStripe stripe,
                           ServicioFcm fcm) {
        this.fuente = fuente;
        this.repositorio = repositorio;
        this.stripe = stripe;
        this.fcm = fcm;
    }

    @Override
    public DescriptorApp descriptor() {
        List<DescriptorApp.Capacidad> capacidades = new ArrayList<>(List.of(
                DescriptorApp.Capacidad.USUARIOS,
                DescriptorApp.Capacidad.METRICAS,
                DescriptorApp.Capacidad.ACCIONES,
                DescriptorApp.Capacidad.BORRAR_USUARIOS));

        // La pestaña de dinero solo aparece si hay de dónde sacarlo. Una pestaña
        // vacía y permanente enseña menos que no tenerla.
        if (stripe.configurado()) {
            capacidades.add(DescriptorApp.Capacidad.INGRESOS);
        }

        return new DescriptorApp(
                "vbstats",
                "VBStats",
                "Estadísticas de voleibol en directo",
                "#2196F3",
                "grafica",
                List.of("ios", "android"),
                capacidades,
                List.of(
                        new DescriptorApp.CampoExtra("equipos", "Equipos"),
                        new DescriptorApp.CampoExtra("partidos", "Partidos"),
                        new DescriptorApp.CampoExtra("acceso", "Entra con"),
                        new DescriptorApp.CampoExtra("pasarela", "Paga por")));
    }

    @Override
    public EstadoConector estado() {
        if (!fuente.configurado()) {
            return EstadoConector.sinConfigurar(
                    "Falta BLUEDEBUG_VBSTATS_URL con la conexión a la base de datos de VBStats.");
        }
        if (!fuente.disponible()) {
            return EstadoConector.sinConfigurar(
                    "La base de datos de VBStats no responde. Mira si Railway la tiene levantada.");
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
        int suscripciones = repositorio.suscripcionesActivas();
        Map<String, Integer> porPlan = repositorio.usuariosPorPlan();

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
        metricas.add(Metrica.entero("suscripciones", "Suscripciones de pago", suscripciones,
                total == 0 ? "" : porcentajeTexto(suscripciones, total) + " de las cuentas"));
        metricas.add(Metrica.entero("partidos", "Partidos registrados", repositorio.totalPartidos(),
                "en total"));
        metricas.add(Metrica.entero("dispositivos", "Móviles con avisos", repositorio.totalDispositivos(),
                repositorio.usuariosAlcanzables() + " cuentas alcanzables"));

        List<Serie> series = List.of(
                rango.rellenar("altas", "Altas", "entero", altas),
                rango.rellenar("partidos", "Partidos creados", "entero", repositorio.partidosPorDia(rango)),
                rango.rellenar("accesos", "Cuentas con su último acceso ese día", "entero",
                        repositorio.accesosPorDia(rango)));

        List<Reparto> repartos = List.of(
                new Reparto("planes", "Cuentas por plan", List.of(
                        new Reparto.Trozo("Gratis", porPlan.getOrDefault("free", 0), "#64748B"),
                        new Reparto.Trozo("Basic", porPlan.getOrDefault("basic", 0), "#2196F3"),
                        new Reparto.Trozo("Pro", porPlan.getOrDefault("pro", 0), "#0EA5E9"))),
                new Reparto("plataformas", "Dispositivos por plataforma",
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
    public Optional<Ingresos> ingresos(Rango rango) {
        if (!stripe.configurado() || !estado().disponible()) {
            return Optional.empty();
        }

        var cobros = stripe.cobros(rango);
        int suscriptores = repositorio.suscripcionesActivas();

        return Optional.of(new Ingresos(
                "eur",
                cobros.facturado(),
                cobros.devuelto(),
                // Recurrente estimado: lo facturado en los últimos 30 días. No es el
                // MRR contable —una anualidad cobrada de golpe lo dispara ese mes— pero
                // sí es la cifra honesta que se puede dar sin leer cada suscripción de
                // Stripe una por una.
                mensualEstimado(),
                suscriptores,
                cobros.porDia(),
                cobros.porPlan(),
                cobros.movimientos()));
    }

    private double mensualEstimado() {
        return stripe.cobros(Rango.ultimosDias(30)).facturado();
    }

    // ----------------------------------------------------------------- acciones

    private static final String ENVIAR_AVISO = "enviar-notificacion";

    @Override
    public List<AccionAdmin> acciones() {
        return List.of(new AccionAdmin(
                ENVIAR_AVISO,
                "Enviar notificación",
                "Manda un aviso push a los móviles con VBStats instalado. Queda en el historial "
                        + "que la propia app enseña en su panel.",
                "campana",
                false,
                "Enviar",
                List.of(
                        AccionAdmin.Campo.texto("titulo", "Título", 100,
                                "Lo que se lee en negrita en la notificación."),
                        AccionAdmin.Campo.area("cuerpo", "Mensaje", 500, null),
                        AccionAdmin.Campo.seleccion("audiencia", "A quién", "Los planes salen de la base de datos.",
                                List.of(
                                        new AccionAdmin.Campo.Opcion("all", "Todo el mundo", null),
                                        new AccionAdmin.Campo.Opcion("paid", "Solo quien paga", "Basic y Pro"),
                                        new AccionAdmin.Campo.Opcion("free", "Solo plan gratis", null),
                                        new AccionAdmin.Campo.Opcion("basic", "Solo Basic", null),
                                        new AccionAdmin.Campo.Opcion("pro", "Solo Pro", null))))));
    }

    @Override
    public ResultadoAccion ejecutar(String accionId, Map<String, Object> parametros, String emailAdmin) {
        if (!ENVIAR_AVISO.equals(accionId)) {
            return ResultadoAccion.error("VBStats no conoce la acción '" + accionId + "'");
        }
        return enviarNotificacion(parametros, emailAdmin);
    }

    private ResultadoAccion enviarNotificacion(Map<String, Object> parametros, String emailAdmin) {
        String titulo = texto(parametros, "titulo");
        String cuerpo = texto(parametros, "cuerpo");
        String audiencia = texto(parametros, "audiencia");

        if (titulo.isBlank() || cuerpo.isBlank()) {
            throw new PeticionInvalida("El título y el mensaje son obligatorios");
        }
        if (titulo.length() > 100 || cuerpo.length() > 500) {
            throw new PeticionInvalida("El título admite 100 caracteres y el mensaje 500");
        }
        if (!List.of("all", "free", "basic", "pro", "paid").contains(audiencia)) {
            throw new PeticionInvalida("Esa audiencia no existe");
        }
        if (!fcm.configurado()) {
            return ResultadoAccion.error(
                    "Falta BLUEDEBUG_VBSTATS_FIREBASE con la cuenta de servicio; sin ella no se puede enviar.");
        }

        List<String> tokens = repositorio.tokensDe(audiencia);
        if (tokens.isEmpty()) {
            return ResultadoAccion.error("No hay ningún móvil registrado en esa audiencia");
        }

        // Se registra ANTES de enviar. Si el envío se cae por la mitad, tiene que
        // quedar constancia de que se intentó: un historial que solo apunta los
        // envíos perfectos es justo el que no sirve cuando algo va mal.
        Integer registro = repositorio.registrarNotificacion(titulo, cuerpo, audiencia, emailAdmin);

        ServicioFcm.Envio envio = fcm.enviar(tokens, titulo, cuerpo);

        if (registro != null) {
            repositorio.ajustarDestinatarios(registro, envio.entregados());
        }

        int limpiados = repositorio.limpiarTokens(envio.tokensCaducados());
        if (limpiados > 0) {
            log.info("VBStats: borrados {} tokens que FCM da por muertos", limpiados);
        }

        return ResultadoAccion.correcta("Aviso enviado")
                .con("entregados", envio.entregados())
                .con("fallidos", envio.fallidos())
                .con("dispositivos", tokens.size())
                .con("tokensLimpiados", limpiados)
                .con("registrado", registro != null)
                .listo();
    }

    @Override
    public ResultadoAccion borrarUsuario(String usuarioId, String emailAdmin) {
        int id;
        try {
            id = Integer.parseInt(usuarioId);
        } catch (NumberFormatException e) {
            throw new PeticionInvalida("El id de usuario de VBStats es un número");
        }

        Map<String, Object> usuario = repositorio.resumenUsuario(id);
        if (usuario.isEmpty()) {
            throw new PeticionInvalida("Esa cuenta no existe");
        }
        // Un superadmin no se borra desde aquí. Es la misma regla que aplica el
        // panel de la propia app, y protege del clic de más: recuperar esa cuenta
        // significa volver a crearla a mano en la base de datos.
        if (Boolean.TRUE.equals(usuario.get("is_superadmin"))
                || Integer.valueOf(1).equals(usuario.get("is_superadmin"))) {
            return ResultadoAccion.error("Esa cuenta es superadmin de VBStats; no se borra desde el panel");
        }

        repositorio.borrarUsuario(id);
        log.warn("AUDITORÍA: {} borró la cuenta {} ({}) de VBStats", emailAdmin, id, usuario.get("email"));

        return ResultadoAccion.ok("Cuenta " + usuario.get("email") + " borrada con todos sus datos");
    }

    @Override
    public List<com.bluedebug.gestion.conectores.modelo.Tabla> tablas() {
        if (!estado().disponible()) {
            return List.of();
        }
        return List.of(new com.bluedebug.gestion.conectores.modelo.Tabla(
                "avisos",
                "Historial de avisos",
                List.of(
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.fecha("enviado", "Enviado"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("titulo", "Título"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("cuerpo", "Mensaje"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("audiencia", "Audiencia"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.entero("destinatarios", "Llegó a"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("enviadoPor", "Lo mandó")),
                repositorio.historialNotificaciones(25),
                "Todavía no se ha mandado ningún aviso"));
    }

    // ------------------------------------------------------------------ apoyo

    private String texto(Map<String, Object> parametros, String clave) {
        Object valor = parametros.get(clave);
        return valor == null ? "" : String.valueOf(valor).trim();
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
