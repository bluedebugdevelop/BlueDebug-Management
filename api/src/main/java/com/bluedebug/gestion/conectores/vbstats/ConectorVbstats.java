package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.comun.ServicioTraduccion;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ServicioAppStore appStore;
    private final ServicioFcm fcm;
    private final ServicioTraduccion traduccion;
    private final ObjectMapper json;

    public ConectorVbstats(FuenteVbstats fuente,
                           RepositorioVbstats repositorio,
                           ServicioStripe stripe,
                           ServicioAppStore appStore,
                           ServicioFcm fcm,
                           ServicioTraduccion traduccion,
                           ObjectMapper json) {
        this.fuente = fuente;
        this.repositorio = repositorio;
        this.stripe = stripe;
        this.appStore = appStore;
        this.fcm = fcm;
        this.traduccion = traduccion;
        this.json = json;
    }

    @Override
    public DescriptorApp descriptor() {
        List<DescriptorApp.Capacidad> capacidades = new ArrayList<>(List.of(
                DescriptorApp.Capacidad.USUARIOS,
                DescriptorApp.Capacidad.METRICAS,
                DescriptorApp.Capacidad.ACCIONES,
                DescriptorApp.Capacidad.BORRAR_USUARIOS,
                DescriptorApp.Capacidad.EDITAR_ROL));

        // La pestaña de dinero solo aparece si hay de dónde sacarlo. Una pestaña
        // vacía y permanente enseña menos que no tenerla. Basta con que haya UNA
        // de las dos pasarelas: se enseña lo que se sepa.
        if (stripe.configurado() || appStore.configurado()) {
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

    /**
     * El dinero, juntando las DOS pasarelas.
     *
     * VBStats cobra por Stripe y por la App Store, y durante un tiempo el panel
     * solo veía la primera: enseñaba una facturación que se dejaba fuera a todos
     * los suscriptores de iPhone. Ahora se suman, y las dos cifras son
     * comparables porque las dos son el precio que pagó el cliente antes de
     * comisiones (ver {@link ServicioAppStore}).
     */
    @Override
    public Optional<Ingresos> ingresos(Rango rango) {
        if (!estado().disponible() || (!stripe.configurado() && !appStore.configurado())) {
            return Optional.empty();
        }

        var deStripe = stripe.cobros(rango);
        var deApple = appStore.cobros(rango, cuentasApple());

        List<Ingresos.Movimiento> movimientos = new ArrayList<>(deStripe.movimientos());
        movimientos.addAll(deApple.movimientos());
        movimientos.sort((a, b) -> b.fecha().compareTo(a.fecha()));

        // La serie diaria se suma día a día: son los mismos días del mismo rango,
        // así que basta con emparejarlos por fecha.
        Map<java.time.LocalDate, Double> porDia = new java.util.HashMap<>();
        for (Serie.Punto p : deStripe.porDia().puntos()) {
            porDia.merge(p.fecha(), p.valor(), Double::sum);
        }
        for (Ingresos.Movimiento m : deApple.movimientos()) {
            if (m.importe() > 0) {
                porDia.merge(rango.diaDe(m.fecha()), m.importe(), Double::sum);
            }
        }

        // Y el reparto gana una categoría: cuánto entra por cada pasarela, que es
        // lo primero que se quiere mirar cuando por fin están las dos.
        List<Reparto.Trozo> porPasarela = new ArrayList<>();
        if (deStripe.facturado() > 0) {
            porPasarela.add(new Reparto.Trozo("Stripe", deStripe.facturado(), "#635BFF"));
        }
        if (deApple.facturado() > 0) {
            porPasarela.add(new Reparto.Trozo("App Store", deApple.facturado(), "#A2AAAD"));
        }

        return Optional.of(new Ingresos(
                "eur",
                redondear(deStripe.facturado() + deApple.facturado()),
                redondear(deStripe.devuelto() + deApple.devuelto()),
                mensualEstimado(),
                repositorio.suscripcionesActivas(),
                rango.rellenar("facturado", "Facturado", "dinero", porDia),
                porPasarela.isEmpty()
                        ? deStripe.porPlan()
                        : new Reparto("por_pasarela", "Facturación por pasarela", porPasarela),
                movimientos.stream().limit(50).toList()));
    }

    private List<ServicioAppStore.CuentaApple> cuentasApple() {
        if (!appStore.configurado()) {
            return List.of();
        }
        try {
            return repositorio.cuentasConApple();
        } catch (Exception e) {
            log.warn("VBStats: no se pudieron leer las cuentas de Apple: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Recurrente estimado: lo facturado por las dos pasarelas en los últimos 30
     * días. No es el MRR contable —una anualidad cobrada de golpe lo dispara ese
     * mes— pero sí es la cifra honesta que se puede dar sin leer una por una todas
     * las suscripciones de Stripe y de Apple.
     */
    private double mensualEstimado() {
        Rango mes = Rango.ultimosDias(30);
        return redondear(stripe.cobros(mes).facturado() + appStore.cobros(mes, cuentasApple()).facturado());
    }

    private double redondear(double valor) {
        return Math.round(valor * 100d) / 100d;
    }

    // ----------------------------------------------------------------- acciones

    private static final String ENVIAR_AVISO = "enviar-notificacion";
    private static final String PUBLICAR_NOVEDADES = "publicar-novedades";

    @Override
    public List<AccionAdmin> acciones() {
        return List.of(avisoPush(), publicarNovedades());
    }

    private AccionAdmin avisoPush() {
        return new AccionAdmin(
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
                                        new AccionAdmin.Campo.Opcion("pro", "Solo Pro", null)))));
    }

    @Override
    public ResultadoAccion ejecutar(String accionId, Map<String, Object> parametros, String emailAdmin) {
        return switch (accionId) {
            case ENVIAR_AVISO -> enviarNotificacion(parametros, emailAdmin);
            case PUBLICAR_NOVEDADES -> publicarNovedades(parametros, emailAdmin);
            default -> ResultadoAccion.error("VBStats no conoce la acción '" + accionId + "'");
        };
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

    // ------------------------------------------------------- novedades de versión

    /**
     * Lo que va a las tiendas es una app de voleibol, y el traductor lo tiene que
     * saber: sin esta línea, «posición», «set» o «recepción» salen traducidos por
     * su acepción común y no por la del deporte.
     */
    private static final String CONTEXTO_NOVEDADES = """
            VBStats, una app móvil de estadísticas de voleibol en directo. Las líneas son la lista de \
            novedades que la app enseña una sola vez después de actualizarse, cada una un titular corto \
            que se lee solo, sin explicación debajo. Vocabulario de voleibol y de app móvil.""";

    private AccionAdmin publicarNovedades() {
        String ayudaIdiomas = traduccion.configurado()
                ? "Se traduce solo al inglés, el francés y el portugués al publicar (con "
                        + traduccion.motor() + ")."
                : "AVISO: el panel no tiene traductor configurado (falta GEMINI_API_KEY o "
                        + "ANTHROPIC_API_KEY), así que esto se publicará solo en castellano y los "
                        + "cuatro idiomas de la app lo leerán en castellano.";

        return new AccionAdmin(
                PUBLICAR_NOVEDADES,
                "Publicar novedades",
                "Escribe el «qué hay de nuevo» que la app enseña una sola vez tras actualizar. "
                        + "Solo lo ve quien tenga instalada exactamente esa versión, así que se puede "
                        + "dejar preparado antes de que el build llegue a las tiendas. Es el mismo "
                        + "resumen que edita el panel admin de la app: publicar aquí lo reescribe.",
                "movil",
                false,
                "Publicar",
                List.of(
                        AccionAdmin.Campo.texto("version", "Versión", 12,
                                "La misma que lleva el build publicado (5.5, por ejemplo). Si no coincide "
                                        + "con la instalada, esa app no enseña nada."),
                        AccionAdmin.Campo.area("es", "Novedades", 900,
                                "Una línea por novedad, hasta " + NovedadesVbstats.MAXIMO_NOVEDADES
                                        + ". " + ayudaIdiomas + " Para elegir icono, empieza la línea con "
                                        + "su nombre y una barra: «mejora | Copia una posición a todas las "
                                        + "demás». Iconos: " + NovedadesVbstats.iconosDisponibles() + "."),
                        AccionAdmin.Campo.seleccion("estado", "Estado",
                                "Oculta guarda el texto sin enseñarlo: sirve para dejarlo escrito antes de "
                                        + "que la versión salga.",
                                List.of(
                                        new AccionAdmin.Campo.Opcion("publicada", "Publicada", "La app la enseña"),
                                        new AccionAdmin.Campo.Opcion("oculta", "Oculta", "Guardada sin enseñar")))));
    }

    /**
     * Publica las novedades, traduciéndolas por el camino.
     *
     * La traducción va aquí dentro y no en un botón aparte a propósito: escribir
     * lo mismo cuatro veces es exactamente por lo que tres de los cuatro idiomas
     * acaban vacíos, y como el servidor cae al castellano cuando falta uno, eso no
     * se nota nunca. Al hacerlo en el guardado, la única forma de publicar sin
     * traducir es que el traductor falle, y entonces se dice.
     */
    private ResultadoAccion publicarNovedades(Map<String, Object> parametros, String emailAdmin) {
        String version = texto(parametros, "version");
        if (!version.matches("\\d+(\\.\\d+){0,2}")) {
            throw new PeticionInvalida("La versión se escribe como 5.5 o 5.5.1, sin letras");
        }

        String castellano = texto(parametros, "es");

        // Se parte en líneas ANTES de traducir, con las mismas reglas que al
        // guardar. Si lo escrito no vale —una línea que no cabe, un icono que no
        // existe—, el error que sale es ese y no se gasta una llamada al traductor.
        List<String> titulares = NovedadesVbstats.titulares(castellano);
        if (titulares.isEmpty()) {
            throw new PeticionInvalida("Hay que escribir al menos una novedad");
        }

        var traducido = traduccion.traducir(titulares, CONTEXTO_NOVEDADES, NovedadesVbstats.MAXIMO_TITULAR);
        NovedadesVbstats.Publicacion publicacion =
                NovedadesVbstats.preparar(json, castellano, traducido.porIdioma());

        boolean publicada = !"oculta".equals(texto(parametros, "estado"));

        if (!repositorio.publicarNovedades(version, publicacion.itemsJson(), publicada, emailAdmin)) {
            return ResultadoAccion.error(
                    "La base de datos de VBStats todavía no tiene la tabla whats_new_releases. No la "
                            + "crea el arranque del backend: hay que lanzar su migración, "
                            + "node db/run_whats_new_migration.js.");
        }

        log.warn("AUDITORÍA: {} publicó las novedades de VBStats {} ({} líneas, {} idiomas, {})",
                emailAdmin, version, publicacion.novedades(), publicacion.idiomas(),
                publicada ? "publicada" : "oculta");

        return ResultadoAccion.correcta(mensajePublicacion(version, publicada, publicacion, traducido))
                .con("novedades", publicacion.novedades())
                .con("idiomas", publicacion.idiomas())
                .listo();
    }

    /**
     * Lo que se lee debajo del botón.
     *
     * Que falte una traducción NO es un fallo que impida publicar —la app enseña
     * el castellano y se entiende— pero tampoco puede pasar en silencio: es el
     * error que no se ve, porque en pantalla todo salió verde y el que lo nota es
     * un portugués tres semanas después.
     */
    private String mensajePublicacion(String version,
                                      boolean publicada,
                                      NovedadesVbstats.Publicacion publicacion,
                                      com.bluedebug.gestion.comun.ServicioTraduccion.Resultado traducido) {

        String base = publicada
                ? "Novedades de la " + version + " publicadas"
                : "Novedades de la " + version + " guardadas sin publicar";

        if (publicacion.sinTraducir() == 0) {
            return base + " en los cuatro idiomas";
        }
        return base + " solo en castellano: " + traducido.mensaje()
                + " Los otros " + publicacion.sinTraducir()
                + " idiomas lo leerán en castellano hasta que se vuelva a publicar.";
    }

    // ------------------------------------------------------------ editar plan

    @Override
    public java.util.Optional<com.bluedebug.gestion.conectores.modelo.EdicionRol> edicionRol() {
        return java.util.Optional.of(new com.bluedebug.gestion.conectores.modelo.EdicionRol(
                "Plan",
                false,
                List.of(
                        com.bluedebug.gestion.conectores.modelo.EdicionRol.Opcion.de("free", "Gratis"),
                        com.bluedebug.gestion.conectores.modelo.EdicionRol.Opcion.de("basic", "Basic"),
                        com.bluedebug.gestion.conectores.modelo.EdicionRol.Opcion.de("pro", "Pro")),
                "Esto cambia el plan en la base de datos, pero no cobra ni cancela nada en "
                        + "Stripe ni en la App Store. Y ojo: si la cuenta tiene suscripción activa "
                        + "en una pasarela, la propia app vuelve a poner el plan que diga la "
                        + "pasarela la próxima vez que consulte su estado."));
    }

    @Override
    public ResultadoAccion cambiarRol(String usuarioId, List<String> roles, String emailAdmin) {
        int id;
        try {
            id = Integer.parseInt(usuarioId);
        } catch (NumberFormatException e) {
            throw new PeticionInvalida("El id de usuario de VBStats es un número");
        }

        // Un solo valor: VBStats declara `multiple = false`.
        if (roles == null || roles.size() != 1) {
            throw new PeticionInvalida("Hay que indicar exactamente un plan");
        }
        String plan = roles.get(0);
        if (!edicionRol().orElseThrow().admite(plan)) {
            throw new PeticionInvalida("Ese plan no existe en VBStats");
        }

        Map<String, Object> antes = repositorio.planDe(id);
        if (antes.isEmpty()) {
            throw new PeticionInvalida("Esa cuenta no existe");
        }

        String planAnterior = String.valueOf(antes.get("subscription_type"));
        if (plan.equals(planAnterior)) {
            return ResultadoAccion.ok("Ya estaba en " + plan);
        }

        repositorio.cambiarPlan(id, plan);

        log.warn("AUDITORÍA: {} cambió el plan de {} ({}) de {} a {}",
                emailAdmin, id, antes.get("email"), planAnterior, plan);

        // Si paga por una pasarela, el cambio puede no durar: la app resincroniza
        // el plan con lo que diga Stripe o Apple. Es mejor decirlo en el momento
        // que dejar que lo descubra al ver que el plan «se ha vuelto solo» atrás.
        boolean conPasarela = esVerdad(antes.get("tieneStripe")) || esVerdad(antes.get("tieneApple"));
        if (conPasarela) {
            return ResultadoAccion.ok("Plan cambiado a " + plan
                    + ", pero esta cuenta tiene suscripción en una pasarela: la app puede volver a "
                    + "ponerle el plan que diga Stripe o Apple.");
        }
        return ResultadoAccion.ok("Plan cambiado de " + planAnterior + " a " + plan);
    }

    /** MySQL devuelve los booleanos calculados como 1/0, no como true/false. */
    private boolean esVerdad(Object valor) {
        if (valor instanceof Boolean b) {
            return b;
        }
        return valor instanceof Number n && n.intValue() != 0;
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
                "novedades",
                "Novedades por versión",
                List.of(
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("version", "Versión"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.entero("novedades", "Líneas"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.entero("idiomas", "Idiomas"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("publicada", "Estado"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("publicadoPor", "La escribió"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.fecha("actualizado", "Actualizada")),
                repositorio.novedadesPublicadas(15),
                "Todavía no se han publicado novedades de ninguna versión"),
                new com.bluedebug.gestion.conectores.modelo.Tabla(
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
