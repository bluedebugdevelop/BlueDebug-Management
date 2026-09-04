package com.bluedebug.gestion.conectores.cvo;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.modelo.AccionAdmin;
import com.bluedebug.gestion.conectores.modelo.AccionAdmin.Campo;
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

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Club Voleibol Oviedo: la app del club.
 *
 * Es un caso distinto de VBStats y por eso el conector no se parece: aquí no hay
 * dinero ni planes, hay socios, equipos y roles. Y las bajas no son borrados:
 * quien deja el club se marca como inactivo y conserva su histórico, así que la
 * acción destructiva del panel no es «borrar», es «dar de baja» —y tiene vuelta
 * atrás.
 *
 * ADMINISTRAR EL CLUB DESDE AQUÍ. Todo lo que un administrador podía hacer solo
 * desde el móvil —dar de alta a alguien, crear un equipo, mover gente entre
 * plantillas— está declarado abajo como acciones. No hay ni una pantalla nueva en
 * Angular: son formularios que el panel construye a partir de estas
 * declaraciones, igual que el de mandar un aviso.
 *
 * Y el panel escribe con el Admin SDK, que no pasa por las reglas de Firestore.
 * Eso NO es un agujero —a este proceso solo llega quien está en la lista blanca
 * de administradores del panel— pero sí obliga a una cosa: las comprobaciones que
 * en la app hacen las reglas hay que repetirlas aquí a mano. Por eso abajo se
 * valida que quede al menos un rol, que el club no se quede sin administradores y
 * que un jugador no acabe en la lista de entrenadores de un equipo.
 */
@Component
public class ConectorCvo implements ConectorApp {

    private static final Logger log = LoggerFactory.getLogger(ConectorCvo.class);

    private static final int DIAS_ACTIVIDAD = 30;

    private static final String ENVIAR_AVISO = "enviar-aviso";
    private static final String CAMBIAR_ALTA = "cambiar-alta";
    private static final String CREAR_USUARIO = "crear-usuario";
    private static final String EDITAR_FICHA = "editar-ficha";
    private static final String RESTABLECER_CLAVE = "restablecer-clave";
    private static final String CREAR_EQUIPO = "crear-equipo";
    private static final String EDITAR_EQUIPO = "editar-equipo";
    private static final String ARCHIVAR_EQUIPO = "archivar-equipo";
    private static final String ANADIR_A_EQUIPO = "anadir-a-equipo";
    private static final String QUITAR_DE_EQUIPO = "quitar-de-equipo";

    private static final String GRUPO_AVISOS = "Avisos";
    private static final String GRUPO_PERSONAS = "Personas";
    private static final String GRUPO_EQUIPOS = "Equipos";
    private static final String GRUPO_PLANTILLAS = "Plantillas";

    /**
     * Lo que hay que escribir en un campo para DEJARLO VACÍO.
     *
     * En los formularios de edición, un campo en blanco significa «no lo toques»:
     * es lo único que permite editar una cosa sin tener que reescribir las otras
     * seis. Pero entonces hace falta otra forma de decir «bórralo», y esa es esta.
     * Va explicado en la ayuda de cada campo donde se admite.
     */
    private static final String VACIAR = "-";

    /** Las mismas que ofrece la app al rellenar una ficha (`modelo.ts`). */
    private static final List<String> POSICIONES =
            List.of("Colocador/a", "Opuesto/a", "Receptor/a", "Central", "Líbero");

    private static final List<String> CATEGORIAS = List.of(
            "Competición nacional", "Sénior", "Junior", "Juvenil", "Cadete",
            "Infantil", "Alevín", "Benjamín", "Veteranos", "Escuela");

    private static final List<String> GENEROS = List.of("Masculino", "Femenino", "Mixto");

    private static final List<String> ROLES = List.of("jugador", "entrenador", "admin");

    private final FuenteCvo fuente;
    private final RepositorioCvo repositorio;
    private final ServicioPushExpo push;

    public ConectorCvo(FuenteCvo fuente, RepositorioCvo repositorio, ServicioPushExpo push) {
        this.fuente = fuente;
        this.repositorio = repositorio;
        this.push = push;
    }

    @Override
    public DescriptorApp descriptor() {
        return new DescriptorApp(
                "cvo",
                "CV Oviedo",
                "App del Club Voleibol Oviedo",
                "#F97316",
                "escudo",
                List.of("ios", "android"),
                List.of(
                        DescriptorApp.Capacidad.USUARIOS,
                        DescriptorApp.Capacidad.METRICAS,
                        DescriptorApp.Capacidad.ACCIONES,
                        DescriptorApp.Capacidad.EDITAR_ROL),
                List.of(
                        new DescriptorApp.CampoExtra("roles", "Roles"),
                        new DescriptorApp.CampoExtra("equipos", "Equipos"),
                        new DescriptorApp.CampoExtra("dorsal", "Dorsal"),
                        new DescriptorApp.CampoExtra("posicion", "Posición")));
    }

    @Override
    public EstadoConector estado() {
        if (!fuente.configurado()) {
            return EstadoConector.sinConfigurar(
                    "Falta BLUEDEBUG_CVO_FIREBASE con la cuenta de servicio del proyecto cv-oviedo.");
        }
        if (!fuente.disponible()) {
            return EstadoConector.sinConfigurar(
                    "Firestore no responde. Comprueba que la cuenta de servicio sigue teniendo permisos.");
        }
        return EstadoConector.listo();
    }

    @Override
    public ResumenApp resumen(Rango rango) {
        EstadoConector estado = estado();
        if (!estado.disponible()) {
            return ResumenApp.noDisponible(descriptor(), estado);
        }

        List<UsuarioApp> gente = repositorio.usuarios();
        List<RepositorioCvo.Equipo> equipos = repositorio.equipos();

        long altas = gente.stream().filter(UsuarioApp::activo).count();
        long bajas = gente.size() - altas;
        var corte = Rango.ultimosDias(DIAS_ACTIVIDAD).inicio();
        long activos = gente.stream()
                .filter(u -> u.ultimaSesion() != null && u.ultimaSesion().isAfter(corte))
                .count();
        long conMovil = gente.stream().filter(u -> u.dispositivos() > 0).count();
        long enActivo = equipos.stream().filter(e -> !e.archivado()).count();

        List<Metrica> metricas = new ArrayList<>();
        metricas.add(Metrica.entero("socios", "Fichas del club", altas,
                bajas == 0 ? "ninguna baja" : bajas + " de baja"));
        metricas.add(Metrica.entero("activos", "Han entrado", activos,
                "en los últimos " + DIAS_ACTIVIDAD + " días"));
        metricas.add(Metrica.entero("equipos", "Equipos", enActivo,
                equipos.size() - enActivo + " archivados"));
        metricas.add(Metrica.entero("moviles", "Con la app instalada", conMovil,
                "reciben los avisos"));
        // Esta cifra merece estar arriba: son personas que se registraron y se
        // quedaron esperando a que alguien les diera ficha. Si sube, es que hay
        // gente sin atender. Se resuelve con «Dar de alta»: al reconocer el correo
        // le crea la ficha que le falta y le deja su contraseña.
        metricas.add(Metrica.entero("sinficha", "Registrados sin ficha", repositorio.cuentasSinFicha(),
                "esperando el alta de un admin"));

        List<Serie> series = List.of(
                rango.rellenar("altas", "Altas", "entero", repositorio.altasPorDia(rango)),
                rango.rellenar("accesos", "Cuentas con su último acceso ese día", "entero",
                        repositorio.accesosPorDia(rango)));

        Map<String, Long> porRol = new LinkedHashMap<>();
        for (UsuarioApp u : gente) {
            porRol.merge(u.plan(), 1L, Long::sum);
        }

        List<Reparto> repartos = List.of(
                new Reparto("roles", "Fichas por rol", List.of(
                        new Reparto.Trozo("Jugadores", porRol.getOrDefault("jugador", 0L), "#F97316"),
                        new Reparto.Trozo("Entrenadores", porRol.getOrDefault("entrenador", 0L), "#2196F3"),
                        new Reparto.Trozo("Admins", porRol.getOrDefault("admin", 0L), "#0EA5E9"))),
                new Reparto("equipos", "Jugadores por equipo",
                        equipos.stream()
                                .filter(e -> !e.archivado())
                                .map(e -> Reparto.Trozo.de(e.nombre(), e.jugadores()))
                                .toList()));

        return new ResumenApp(descriptor(), estado, metricas, series, repartos);
    }

    @Override
    public List<UsuarioApp> usuarios() {
        return estado().disponible() ? repositorio.usuarios() : List.of();
    }

    // ----------------------------------------------------------------- acciones

    /**
     * Todo lo que se puede administrar del club.
     *
     * Los desplegables se llenan con lo que hay en Firestore AHORA MISMO: los
     * equipos que existen, la gente que tiene ficha. Es la diferencia entre una
     * lista que sirve y una que hay que acordarse de actualizar cada temporada.
     */
    @Override
    public List<AccionAdmin> acciones() {
        if (!estado().disponible()) {
            return List.of();
        }

        List<RepositorioCvo.Equipo> equipos = repositorio.equipos();
        List<RepositorioCvo.Ficha> fichas = repositorio.fichas();

        List<AccionAdmin> acciones = new ArrayList<>();
        acciones.add(aviso(equipos));
        acciones.add(altaDePersona(equipos));
        acciones.add(edicionDeFicha(fichas));
        acciones.add(claveNueva(fichas));
        acciones.add(altaDeEquipo());
        acciones.add(edicionDeEquipo(equipos));
        acciones.add(archivado(equipos));
        acciones.add(entradaEnPlantilla(equipos, fichas));
        acciones.add(salidaDePlantilla(equipos, fichas));
        return acciones;
    }

    private AccionAdmin aviso(List<RepositorioCvo.Equipo> equipos) {
        List<Campo.Opcion> destinos = new ArrayList<>(List.of(
                new Campo.Opcion("todos", "Todo el club", null),
                new Campo.Opcion("jugadores", "Solo jugadores", null),
                new Campo.Opcion("entrenadores", "Entrenadores y admins", null),
                new Campo.Opcion("admins", "Solo administradores", null)));

        equipos.stream()
                .filter(e -> !e.archivado())
                .forEach(e -> destinos.add(new Campo.Opcion(
                        e.id(), e.nombre(), e.jugadores() + " jugadores")));

        return new AccionAdmin(
                ENVIAR_AVISO,
                "Enviar aviso",
                "Manda una notificación a los móviles del club. Se salta a quien esté de baja.",
                "campana",
                false,
                "Enviar",
                List.of(
                        Campo.texto("titulo", "Título", 100, null),
                        Campo.area("cuerpo", "Mensaje", 400, null),
                        Campo.seleccion("destino", "A quién",
                                "Los equipos salen de Firestore, así que la lista está siempre al día.",
                                destinos)),
                GRUPO_AVISOS);
    }

    private AccionAdmin altaDePersona(List<RepositorioCvo.Equipo> equipos) {
        List<Campo.Opcion> conEquipo = new ArrayList<>();
        conEquipo.add(new Campo.Opcion("", "Sin equipo por ahora", null));
        equipos.stream()
                .filter(e -> !e.archivado())
                .forEach(e -> conEquipo.add(new Campo.Opcion(e.id(), e.nombre(),
                        e.categoria() + " · " + e.genero())));

        return new AccionAdmin(
                CREAR_USUARIO,
                "Dar de alta",
                "Crea la cuenta y su ficha del club, y devuelve la contraseña para entregársela. "
                        + "Si esa persona ya se había registrado por su cuenta, se le crea la ficha "
                        + "que le faltaba y conserva su contraseña.",
                "usuario-mas",
                false,
                "Dar de alta",
                List.of(
                        Campo.texto("nombre", "Nombre y apellidos", 80, null),
                        Campo.texto("email", "Correo", 120,
                                "Con este correo entrará en la app. No se puede cambiar después."),
                        Campo.multiple("roles", "Roles en el club",
                                "Se puede tener más de uno: quien entrena al infantil puede jugar en el sénior.",
                                opcionesDeRol()),
                        Campo.textoOpcional("telefono", "Teléfono", 20, null),
                        Campo.textoOpcional("dorsal", "Dorsal", 3, "Solo para quien juegue."),
                        new Campo("posicion", "Posición", Campo.Tipo.SELECCION, false, 0,
                                "Solo para quien juegue.", conVacio(POSICIONES, "Sin posición")),
                        new Campo("equipo", "Meter en un equipo", Campo.Tipo.SELECCION, false, 0,
                                "Se puede dejar para luego y hacerlo desde «Añadir a un equipo».",
                                conEquipo),
                        Campo.seleccion("papel", "Y ahí, como",
                                "Un equipo guarda dos listas separadas, y de la de entrenadores sale "
                                        + "quién puede mandar avisos.",
                                List.of(
                                        Campo.Opcion.de("jugador", "Jugador"),
                                        Campo.Opcion.de("entrenador", "Entrenador")))),
                GRUPO_PERSONAS);
    }

    private AccionAdmin edicionDeFicha(List<RepositorioCvo.Ficha> fichas) {
        return new AccionAdmin(
                EDITAR_FICHA,
                "Editar una ficha",
                "Cambia los datos de alguien. Lo que se deje en blanco se queda como está.",
                "editar",
                false,
                "Guardar",
                List.of(
                        Campo.seleccion("uid", "De quién", null, opcionesDePersona(fichas)),
                        Campo.textoOpcional("nombre", "Nombre y apellidos", 80, null),
                        Campo.textoOpcional("telefono", "Teléfono", 20,
                                "Escribe «" + VACIAR + "» para dejarlo vacío."),
                        Campo.textoOpcional("dorsal", "Dorsal", 3,
                                "Escribe «" + VACIAR + "» para dejarlo vacío."),
                        new Campo("posicion", "Posición", Campo.Tipo.SELECCION, false, 0, null,
                                conSinCambios(POSICIONES, "Quitar la posición"))),
                GRUPO_PERSONAS);
    }

    private AccionAdmin claveNueva(List<RepositorioCvo.Ficha> fichas) {
        return new AccionAdmin(
                RESTABLECER_CLAVE,
                "Nueva contraseña",
                "Le pone una contraseña nueva y la enseña aquí para dársela. La anterior deja de valer.",
                "llave",
                true,
                "Cambiar la contraseña",
                List.of(Campo.seleccion("uid", "De quién", null, opcionesDePersona(fichas))),
                GRUPO_PERSONAS);
    }

    private AccionAdmin altaDeEquipo() {
        return new AccionAdmin(
                CREAR_EQUIPO,
                "Crear un equipo",
                "Nace vacío: la plantilla se rellena después desde «Añadir a un equipo».",
                "mas",
                false,
                "Crear",
                List.of(
                        Campo.texto("nombre", "Nombre", 60, "El que se verá en la app."),
                        Campo.seleccion("categoria", "Categoría", null, opciones(CATEGORIAS)),
                        Campo.seleccion("genero", "Género", null, opciones(GENEROS)),
                        Campo.seleccion("temporada", "Temporada", null, opciones(temporadas())),
                        Campo.textoOpcional("claveCompeticion", "Clave de competición", 60,
                                "Enlaza el equipo con los datos de la federación. En blanco si no compite.")),
                GRUPO_EQUIPOS);
    }

    private AccionAdmin edicionDeEquipo(List<RepositorioCvo.Equipo> equipos) {
        return new AccionAdmin(
                EDITAR_EQUIPO,
                "Editar un equipo",
                "Cambia sus datos. Lo que se deje en blanco se queda como está.",
                "editar",
                false,
                "Guardar",
                List.of(
                        Campo.seleccion("equipo", "Cuál", null, opcionesDeEquipo(equipos, true)),
                        Campo.textoOpcional("nombre", "Nombre", 60, null),
                        new Campo("categoria", "Categoría", Campo.Tipo.SELECCION, false, 0, null,
                                conSinCambios(CATEGORIAS, null)),
                        new Campo("genero", "Género", Campo.Tipo.SELECCION, false, 0, null,
                                conSinCambios(GENEROS, null)),
                        new Campo("temporada", "Temporada", Campo.Tipo.SELECCION, false, 0, null,
                                conSinCambios(temporadas(), null)),
                        Campo.textoOpcional("claveCompeticion", "Clave de competición", 60,
                                "Escribe «" + VACIAR + "» para desenlazarlo de la federación."),
                        Campo.textoOpcional("slugWeb", "Ficha de la web", 60,
                                "El slug de la página del equipo en clubvoleiboloviedo.com, a la que "
                                        + "publica su plantilla. «" + VACIAR + "» para desenlazarla.")),
                GRUPO_EQUIPOS);
    }

    private AccionAdmin archivado(List<RepositorioCvo.Equipo> equipos) {
        return new AccionAdmin(
                ARCHIVAR_EQUIPO,
                "Archivar o recuperar",
                "Un equipo archivado desaparece de la app, pero conserva su chat, sus avisos y su "
                        + "horario. Es lo que se hace al acabar la temporada; no hay borrado.",
                "archivo",
                false,
                "Guardar",
                List.of(
                        Campo.seleccion("equipo", "Cuál", null, opcionesDeEquipo(equipos, true)),
                        Campo.interruptor("archivado", "Archivado",
                                "Apagado lo devuelve a la app con su gente intacta.")),
                GRUPO_EQUIPOS);
    }

    private AccionAdmin entradaEnPlantilla(List<RepositorioCvo.Equipo> equipos,
                                           List<RepositorioCvo.Ficha> fichas) {
        return new AccionAdmin(
                ANADIR_A_EQUIPO,
                "Añadir a un equipo",
                "Mete a alguien en la plantilla. Con eso pasa a ver su calendario, su chat y sus avisos.",
                "usuarios",
                false,
                "Añadir",
                List.of(
                        Campo.seleccion("equipo", "A qué equipo", null, opcionesDeEquipo(equipos, false)),
                        Campo.seleccion("uid", "A quién", null, opcionesDePersona(fichas)),
                        Campo.seleccion("papel", "Como",
                                "Entrenador es lo que da mando en ESE equipo: avisos, horario y "
                                        + "convocatorias. El rol de club por sí solo no lo da.",
                                List.of(
                                        Campo.Opcion.de("jugador", "Jugador"),
                                        Campo.Opcion.de("entrenador", "Entrenador")))),
                GRUPO_PLANTILLAS);
    }

    private AccionAdmin salidaDePlantilla(List<RepositorioCvo.Equipo> equipos,
                                          List<RepositorioCvo.Ficha> fichas) {
        return new AccionAdmin(
                QUITAR_DE_EQUIPO,
                "Sacar de un equipo",
                "Le quita el acceso al chat y a los avisos de ese equipo. Su ficha del club y su "
                        + "histórico no se tocan.",
                "menos",
                true,
                "Sacar",
                List.of(
                        Campo.seleccion("equipo", "De qué equipo", null, opcionesDeEquipo(equipos, true)),
                        Campo.seleccion("uid", "A quién", null, opcionesDePersona(fichas))),
                GRUPO_PLANTILLAS);
    }

    @Override
    public ResultadoAccion ejecutar(String accionId, Map<String, Object> parametros, String emailAdmin) {
        return switch (accionId) {
            case ENVIAR_AVISO -> enviarAviso(parametros, emailAdmin);
            case CAMBIAR_ALTA -> cambiarAlta(parametros, emailAdmin);
            case CREAR_USUARIO -> crearUsuario(parametros, emailAdmin);
            case EDITAR_FICHA -> editarFicha(parametros, emailAdmin);
            case RESTABLECER_CLAVE -> restablecerClave(parametros, emailAdmin);
            case CREAR_EQUIPO -> crearEquipo(parametros, emailAdmin);
            case EDITAR_EQUIPO -> editarEquipo(parametros, emailAdmin);
            case ARCHIVAR_EQUIPO -> archivarEquipo(parametros, emailAdmin);
            case ANADIR_A_EQUIPO -> anadirAEquipo(parametros, emailAdmin);
            case QUITAR_DE_EQUIPO -> quitarDeEquipo(parametros, emailAdmin);
            default -> ResultadoAccion.error("CVO no conoce la acción '" + accionId + "'");
        };
    }

    // --------------------------------------------------------------- avisos

    private ResultadoAccion enviarAviso(Map<String, Object> parametros, String emailAdmin) {
        String titulo = texto(parametros, "titulo");
        String cuerpo = texto(parametros, "cuerpo");
        String destino = texto(parametros, "destino");

        if (titulo.isBlank() || cuerpo.isBlank()) {
            throw new PeticionInvalida("El título y el mensaje son obligatorios");
        }
        if (titulo.length() > 100 || cuerpo.length() > 400) {
            throw new PeticionInvalida("El título admite 100 caracteres y el mensaje 400");
        }
        if (destino.isBlank()) {
            throw new PeticionInvalida("Hay que elegir a quién va el aviso");
        }

        List<String> tokens = repositorio.tokensPara(destino);
        if (tokens.isEmpty()) {
            return ResultadoAccion.error("Nadie de ese grupo tiene la app instalada");
        }

        ServicioPushExpo.Envio envio = push.enviar(tokens, titulo, cuerpo);

        // Los tokens que Expo da por muertos se borran de las fichas. Si no, cada
        // reinstalación deja uno colgando para siempre y los recuentos mienten.
        int limpiados = repositorio.limpiarTokens(envio.tokensMuertos());

        // El club no tiene tabla de historial como VBStats, así que la única traza
        // de esto es el log del servidor. Queda escrito con el correo de quien lo
        // mandó, que es lo mínimo exigible para una acción que le suena el móvil a
        // medio club.
        log.info("AUDITORÍA: {} envió un aviso de CVO a '{}' ({} dispositivos, {} confirmados, motivos={})",
                emailAdmin, destino, tokens.size(), envio.confirmados(), envio.motivos());

        // El mensaje dice lo que de verdad ha pasado, no lo que se ha intentado.
        // «Aviso enviado» con seis fallos escondidos debajo es exactamente lo que
        // hace que nadie se fíe del panel.
        boolean fracaso = envio.confirmados() == 0 && envio.fallidos() > 0;

        String mensaje;
        if (fracaso) {
            mensaje = "No llegó a ningún móvil";
        } else if (envio.fallidos() > 0) {
            mensaje = "Llegó a " + envio.confirmados() + " de " + tokens.size() + " dispositivos";
        } else if (envio.sinConfirmar() > 0) {
            mensaje = "Aceptado por Expo; " + envio.sinConfirmar() + " sin confirmar todavía";
        } else {
            mensaje = "Entregado en " + envio.confirmados() + " dispositivos";
        }

        var resultado = (fracaso ? ResultadoAccion.fallida(mensaje) : ResultadoAccion.correcta(mensaje))
                .con("dispositivos", tokens.size())
                .con("confirmados", envio.confirmados())
                .con("sinConfirmar", envio.sinConfirmar())
                .con("fallidos", envio.fallidos())
                .con("tokensLimpiados", limpiados);

        // Los motivos son lo único que explica un envío que no llega. Van con su
        // nombre tal cual lo da Expo, que es lo que se puede buscar.
        envio.motivos().forEach((error, cuantos) -> resultado.con("motivo: " + error, cuantos));

        return resultado.listo();
    }

    // -------------------------------------------------------------- personas

    /**
     * Da de alta a alguien: cuenta de Firebase Auth + ficha del club.
     *
     * Las dos cosas hacen falta y ninguna sirve sola. La cuenta permite entrar; la
     * ficha es lo que da acceso a algo, porque las reglas de Firestore no dejan
     * leer ni escribir nada a quien no la tenga.
     *
     * Si la cuenta se crea y la ficha falla, se DESHACE la cuenta. Es la diferencia
     * con lo que hace la app, que la deja huérfana: alguien con credenciales que no
     * abren nada, y el correo ocupado para siempre porque Auth no admite dos
     * cuentas con el mismo. Aquí, al haber Admin SDK, se puede limpiar.
     */
    private ResultadoAccion crearUsuario(Map<String, Object> parametros, String emailAdmin) {
        String nombre = texto(parametros, "nombre");
        String email = texto(parametros, "email").toLowerCase();
        List<String> roles = roles(parametros);
        String telefono = texto(parametros, "telefono");
        String dorsal = texto(parametros, "dorsal");
        String posicion = texto(parametros, "posicion");
        String equipoId = texto(parametros, "equipo");
        String papel = texto(parametros, "papel");

        if (nombre.length() < 3) {
            throw new PeticionInvalida("El nombre tiene que tener al menos 3 letras");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new PeticionInvalida("Ese correo no tiene forma de correo");
        }
        if (repositorio.emailConFicha(email)) {
            throw new PeticionInvalida("Ya hay una ficha en el club con ese correo");
        }

        boolean juega = roles.contains("jugador");
        if (!equipoId.isBlank()) {
            exigirEquipo(equipoId);
            exigirPapelCoherente(papel, roles);
        }

        // Puede tener cuenta ya: quien se registró por su cuenta y se quedó
        // esperando ficha. Se reaprovecha su uid, que es lo único que se puede
        // hacer —Auth no admite dos cuentas con el mismo correo— y se le deja su
        // contraseña, que es suya y funciona.
        String yaExiste = repositorio.uidPorEmail(email);
        String clave = yaExiste == null ? inventarClave() : null;
        String uid;

        try {
            uid = yaExiste != null ? yaExiste : repositorio.crearCuenta(nombre, email, clave);
        } catch (Exception e) {
            log.error("CVO: no se pudo crear la cuenta de {}", email, e);
            return ResultadoAccion.error("No se pudo crear la cuenta: " + e.getMessage());
        }

        Map<String, Object> ficha = new LinkedHashMap<>();
        ficha.put("nombre", nombre);
        ficha.put("email", email);
        ficha.put("roles", roles);
        ficha.put("equipos", List.of());
        ficha.put("dorsal", juega ? dorsal : "");
        ficha.put("posicion", juega ? posicion : "");
        ficha.put("telefono", telefono);
        ficha.put("creadoPor", "panel:" + emailAdmin);

        try {
            repositorio.crearFicha(uid, ficha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deshacerCuenta(yaExiste == null, uid);
            return ResultadoAccion.error("Se interrumpió el alta; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: la cuenta de {} se creó pero la ficha no", email, e);
            deshacerCuenta(yaExiste == null, uid);
            return ResultadoAccion.error("No se pudo escribir la ficha del club; el alta se ha deshecho");
        }

        // El equipo se escribe aparte y a propósito: `anadirAEquipo` toca las DOS
        // caras —la lista del equipo y la del usuario— y son las de la lista del
        // equipo las que miran las reglas. Ponerlo solo en la ficha dejaría a la
        // persona creyendo que está en el equipo mientras el chat le dice que no.
        String enEquipo = null;
        if (!equipoId.isBlank()) {
            try {
                repositorio.anadirAEquipo(equipoId, uid, "entrenador".equals(papel));
                enEquipo = nombreDeEquipo(equipoId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("CVO: {} quedó dado de alta pero fuera del equipo {}: {}",
                        email, equipoId, e.getMessage());
            }
        }

        log.warn("AUDITORÍA: {} dio de alta a {} ({}) con roles {}", emailAdmin, nombre, email, roles);

        var resultado = ResultadoAccion.correcta(yaExiste != null
                        ? nombre + " ya tenía cuenta: ahora tiene ficha y entra con su contraseña de siempre"
                        : nombre + " queda dado de alta")
                .con("Correo", email);

        if (clave != null) {
            resultado.con("Contraseña", clave);
        }
        if (enEquipo != null) {
            resultado.con("Equipo", enEquipo + " (" + papel + ")");
        } else if (!equipoId.isBlank()) {
            resultado.con("Equipo", "no se pudo meter; hazlo desde «Añadir a un equipo»");
        }

        return resultado.listo();
    }

    /** Solo se borra la cuenta si la acabábamos de crear nosotros. */
    private void deshacerCuenta(boolean laCreamosNosotros, String uid) {
        if (!laCreamosNosotros) {
            return;
        }
        try {
            repositorio.borrarCuenta(uid);
        } catch (Exception e) {
            log.error("CVO: quedó una cuenta huérfana en Auth ({}): {}", uid, e.getMessage());
        }
    }

    private ResultadoAccion editarFicha(Map<String, Object> parametros, String emailAdmin) {
        String uid = texto(parametros, "uid");
        Map<String, Object> ficha = exigirFicha(uid);

        Map<String, Object> cambios = new LinkedHashMap<>();
        String nombre = texto(parametros, "nombre");
        if (!nombre.isBlank()) {
            if (nombre.length() < 3) {
                throw new PeticionInvalida("El nombre tiene que tener al menos 3 letras");
            }
            cambios.put("nombre", nombre);
        }
        ponerSiViene(cambios, "telefono", texto(parametros, "telefono"));
        ponerSiViene(cambios, "dorsal", texto(parametros, "dorsal"));
        ponerSiViene(cambios, "posicion", texto(parametros, "posicion"));

        if (cambios.isEmpty()) {
            throw new PeticionInvalida("No has cambiado nada");
        }

        try {
            repositorio.actualizarFicha(uid, cambios);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo editar la ficha {}", uid, e);
            return ResultadoAccion.error("No se pudo guardar la ficha");
        }

        log.warn("AUDITORÍA: {} editó la ficha de {} ({}): {}",
                emailAdmin, uid, ficha.get("email"), cambios.keySet());

        return ResultadoAccion.ok("Ficha de " + ficha.get("nombre") + " actualizada");
    }

    private ResultadoAccion restablecerClave(Map<String, Object> parametros, String emailAdmin) {
        String uid = texto(parametros, "uid");
        Map<String, Object> ficha = exigirFicha(uid);

        String clave = inventarClave();
        try {
            repositorio.cambiarClave(uid, clave);
        } catch (Exception e) {
            log.error("CVO: no se pudo cambiar la contraseña de {}", uid, e);
            return ResultadoAccion.error("No se pudo cambiar la contraseña");
        }

        log.warn("AUDITORÍA: {} cambió la contraseña de {} ({})", emailAdmin, uid, ficha.get("email"));

        return ResultadoAccion.correcta("Contraseña nueva para " + ficha.get("nombre")
                        + ". La anterior ya no vale.")
                .con("Correo", String.valueOf(ficha.get("email")))
                .con("Contraseña", clave)
                .listo();
    }

    private ResultadoAccion cambiarAlta(Map<String, Object> parametros, String emailAdmin) {
        String uid = texto(parametros, "uid");
        boolean activo = Boolean.parseBoolean(texto(parametros, "activo"));

        if (uid.isBlank()) {
            throw new PeticionInvalida("Falta el uid de la ficha");
        }
        Map<String, Object> ficha = repositorio.ficha(uid);
        if (ficha.isEmpty()) {
            throw new PeticionInvalida("Esa ficha no existe en el club");
        }

        try {
            repositorio.cambiarAlta(uid, activo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo cambiar el alta de {}", uid, e);
            return ResultadoAccion.error("No se pudo cambiar el alta");
        }

        log.warn("AUDITORÍA: {} puso a {} ({}) como {}", emailAdmin, uid, ficha.get("email"),
                activo ? "activo" : "de baja");

        return ResultadoAccion.ok(activo
                ? ficha.get("nombre") + " vuelve a tener acceso"
                : ficha.get("nombre") + " queda de baja; conserva su histórico");
    }

    // --------------------------------------------------------------- equipos

    private ResultadoAccion crearEquipo(Map<String, Object> parametros, String emailAdmin) {
        String nombre = texto(parametros, "nombre");
        String categoria = texto(parametros, "categoria");
        String genero = texto(parametros, "genero");
        String temporada = texto(parametros, "temporada");
        String clave = texto(parametros, "claveCompeticion");

        if (nombre.length() < 2) {
            throw new PeticionInvalida("El equipo necesita un nombre");
        }
        exigirDeLaLista(categoria, CATEGORIAS, "categoría");
        exigirDeLaLista(genero, GENEROS, "género");
        if (temporada.isBlank()) {
            throw new PeticionInvalida("Hay que decir de qué temporada es");
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("nombre", nombre);
        datos.put("categoria", categoria);
        datos.put("genero", genero);
        datos.put("temporada", temporada);
        datos.put("claveCompeticion", clave.isBlank() ? null : clave);
        // El enlace con la ficha de la web se hace después y a mano: los dos lados
        // se renombran por su cuenta y adivinarlo por el nombre acaba mal.
        datos.put("slugWeb", null);
        datos.put("creadoPor", "panel:" + emailAdmin);

        String id;
        try {
            id = repositorio.crearEquipo(datos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió la creación; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo crear el equipo {}", nombre, e);
            return ResultadoAccion.error("No se pudo crear el equipo");
        }

        log.warn("AUDITORÍA: {} creó el equipo {} ({})", emailAdmin, nombre, id);

        return ResultadoAccion.correcta(nombre + " creado. Ya se le puede meter gente.")
                .con("Categoría", categoria + " · " + genero)
                .con("Temporada", temporada)
                .listo();
    }

    private ResultadoAccion editarEquipo(Map<String, Object> parametros, String emailAdmin) {
        String id = texto(parametros, "equipo");
        Map<String, Object> equipo = exigirEquipo(id);

        Map<String, Object> cambios = new LinkedHashMap<>();
        String nombre = texto(parametros, "nombre");
        if (!nombre.isBlank()) {
            cambios.put("nombre", nombre);
        }
        String categoria = texto(parametros, "categoria");
        if (!categoria.isBlank()) {
            exigirDeLaLista(categoria, CATEGORIAS, "categoría");
            cambios.put("categoria", categoria);
        }
        String genero = texto(parametros, "genero");
        if (!genero.isBlank()) {
            exigirDeLaLista(genero, GENEROS, "género");
            cambios.put("genero", genero);
        }
        String temporada = texto(parametros, "temporada");
        if (!temporada.isBlank()) {
            cambios.put("temporada", temporada);
        }
        // Estos dos son enlaces: vaciarlos significa desenlazar, y `null` es lo que
        // la app entiende por «este equipo no está enlazado con nada».
        ponerEnlaceSiViene(cambios, "claveCompeticion", texto(parametros, "claveCompeticion"));
        ponerEnlaceSiViene(cambios, "slugWeb", texto(parametros, "slugWeb"));

        if (cambios.isEmpty()) {
            throw new PeticionInvalida("No has cambiado nada");
        }

        try {
            repositorio.actualizarEquipo(id, cambios);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo editar el equipo {}", id, e);
            return ResultadoAccion.error("No se pudo guardar el equipo");
        }

        log.warn("AUDITORÍA: {} editó el equipo {} ({}): {}",
                emailAdmin, id, equipo.get("nombre"), cambios.keySet());

        return ResultadoAccion.ok(cambios.containsKey("nombre")
                ? equipo.get("nombre") + " pasa a llamarse " + cambios.get("nombre")
                : equipo.get("nombre") + " actualizado");
    }

    private ResultadoAccion archivarEquipo(Map<String, Object> parametros, String emailAdmin) {
        String id = texto(parametros, "equipo");
        Map<String, Object> equipo = exigirEquipo(id);
        boolean archivar = bandera(parametros, "archivado");

        if (archivar == Boolean.TRUE.equals(equipo.get("archivado"))) {
            return ResultadoAccion.error(equipo.get("nombre") + " ya estaba "
                    + (archivar ? "archivado" : "en activo"));
        }

        try {
            repositorio.actualizarEquipo(id, Map.of("archivado", archivar));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo archivar el equipo {}", id, e);
            return ResultadoAccion.error("No se pudo cambiar el estado del equipo");
        }

        log.warn("AUDITORÍA: {} {} el equipo {} ({})", emailAdmin,
                archivar ? "archivó" : "recuperó", id, equipo.get("nombre"));

        return ResultadoAccion.ok(archivar
                ? equipo.get("nombre") + " archivado: deja de verse en la app"
                : equipo.get("nombre") + " vuelve a estar en la app con su gente");
    }

    // ------------------------------------------------------------ plantillas

    private ResultadoAccion anadirAEquipo(Map<String, Object> parametros, String emailAdmin) {
        String equipoId = texto(parametros, "equipo");
        String uid = texto(parametros, "uid");
        String papel = texto(parametros, "papel");

        Map<String, Object> equipo = exigirEquipo(equipoId);
        Map<String, Object> ficha = exigirFicha(uid);
        exigirPapelCoherente(papel, rolesDe(ficha));

        if (listaDe(equipo, "jugadores").contains(uid) || listaDe(equipo, "entrenadores").contains(uid)) {
            return ResultadoAccion.error(ficha.get("nombre") + " ya está en " + equipo.get("nombre"));
        }

        try {
            repositorio.anadirAEquipo(equipoId, uid, "entrenador".equals(papel));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo meter a {} en {}", uid, equipoId, e);
            return ResultadoAccion.error("No se pudo meter en el equipo");
        }

        log.warn("AUDITORÍA: {} metió a {} ({}) en {} como {}",
                emailAdmin, uid, ficha.get("email"), equipo.get("nombre"), papel);

        return ResultadoAccion.ok(ficha.get("nombre") + " entra en " + equipo.get("nombre")
                + " como " + papel);
    }

    private ResultadoAccion quitarDeEquipo(Map<String, Object> parametros, String emailAdmin) {
        String equipoId = texto(parametros, "equipo");
        String uid = texto(parametros, "uid");

        Map<String, Object> equipo = exigirEquipo(equipoId);
        Map<String, Object> ficha = exigirFicha(uid);

        if (!listaDe(equipo, "jugadores").contains(uid) && !listaDe(equipo, "entrenadores").contains(uid)) {
            return ResultadoAccion.error(ficha.get("nombre") + " no está en " + equipo.get("nombre"));
        }

        try {
            repositorio.quitarDeEquipo(equipoId, uid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudo sacar a {} de {}", uid, equipoId, e);
            return ResultadoAccion.error("No se pudo sacar del equipo");
        }

        log.warn("AUDITORÍA: {} sacó a {} ({}) de {}",
                emailAdmin, uid, ficha.get("email"), equipo.get("nombre"));

        return ResultadoAccion.ok(ficha.get("nombre") + " sale de " + equipo.get("nombre")
                + "; su ficha del club se queda");
    }

    // ----------------------------------------------------------- editar roles

    @Override
    public Optional<EdicionRol> edicionRol() {
        return Optional.of(new EdicionRol(
                "Roles",
                // En plural y de verdad: la entrenadora del infantil puede jugar en
                // el sénior, y quien lleva la web además entrena a un equipo.
                true,
                List.of(
                        new EdicionRol.Opcion("jugador", "Jugador",
                                "Calendario, horarios, chat y avisos de sus equipos"),
                        new EdicionRol.Opcion("entrenador", "Entrenador",
                                "Puede entrenar equipos: avisos, horarios y convocatorias"),
                        new EdicionRol.Opcion("admin", "Administrador",
                                "Gestiona el club entero: equipos, altas y contenido de la web")),
                "Ser entrenador de club no da mando sobre ningún equipo por sí solo: además hay "
                        + "que estar en la lista de entrenadores de ese equipo, y eso se hace con "
                        + "«Añadir a un equipo»."));
    }

    @Override
    public ResultadoAccion cambiarRol(String uid, List<String> roles, String emailAdmin) {
        if (uid == null || uid.isBlank()) {
            throw new PeticionInvalida("Falta el uid de la ficha");
        }
        if (roles == null || roles.isEmpty()) {
            // Sin roles, esa persona no podría hacer nada en la app y la propia
            // ficha dejaría de tener sentido. La forma de sacar a alguien es la
            // baja, que además tiene vuelta atrás.
            throw new PeticionInvalida("Hay que dejarle al menos un rol. Para sacarle del club, dale de baja.");
        }

        var edicion = edicionRol().orElseThrow();
        for (String rol : roles) {
            if (!edicion.admite(rol)) {
                throw new PeticionInvalida("El rol '" + rol + "' no existe en el club");
            }
        }

        Map<String, Object> ficha = repositorio.ficha(uid);
        if (ficha.isEmpty()) {
            throw new PeticionInvalida("Esa ficha no existe en el club");
        }

        // El club no puede quedarse sin administradores: sin ninguno, nadie puede
        // dar altas ni tocar equipos desde la app, y recuperarlo exige entrar a
        // mano en la consola de Firestore. Se comprueba solo cuando el cambio
        // QUITA el rol de admin a quien lo tenía.
        List<String> rolesAntes = rolesDe(ficha);
        if (rolesAntes.contains("admin") && !roles.contains("admin") && repositorio.cuantosAdmins() <= 1) {
            return ResultadoAccion.error(
                    "Es el único administrador activo del club. Nombra a otro antes de quitarle el rol.");
        }

        try {
            repositorio.cambiarRoles(uid, roles);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoAccion.error("Se interrumpió el cambio; vuelve a intentarlo");
        } catch (Exception e) {
            log.error("CVO: no se pudieron cambiar los roles de {}", uid, e);
            return ResultadoAccion.error("No se pudieron guardar los roles");
        }

        log.warn("AUDITORÍA: {} cambió los roles de {} ({}) de {} a {}",
                emailAdmin, uid, ficha.get("email"), rolesAntes, roles);

        return ResultadoAccion.ok(ficha.get("nombre") + " pasa a ser " + enTexto(roles));
    }

    /** Los roles de una ficha leída en crudo, admitiendo el formato antiguo. */
    @SuppressWarnings("unchecked")
    private List<String> rolesDe(Map<String, Object> ficha) {
        if (ficha.get("roles") instanceof List<?> lista && !lista.isEmpty()) {
            return ((List<Object>) lista).stream().map(String::valueOf).toList();
        }
        Object singular = ficha.get("rol");
        return singular == null ? List.of() : List.of(String.valueOf(singular));
    }

    /** 'jugador y entrenador' — para el mensaje de confirmación. */
    private String enTexto(List<String> roles) {
        var edicion = edicionRol().orElseThrow();
        List<String> nombres = roles.stream()
                .map(r -> edicion.opciones().stream()
                        .filter(o -> o.valor().equals(r))
                        .map(o -> o.etiqueta().toLowerCase())
                        .findFirst().orElse(r))
                .toList();

        if (nombres.size() == 1) {
            return nombres.get(0);
        }
        return String.join(", ", nombres.subList(0, nombres.size() - 1))
                + " y " + nombres.get(nombres.size() - 1);
    }

    /**
     * En CVO «borrar» es dar de baja.
     *
     * El botón de la tabla de usuarios llama aquí, y aquí se traduce a lo que el
     * club entiende por una baja. Se deja explicado porque es justo lo contrario de
     * lo que hace el mismo botón en VBStats, y no verlo llevaría a esperar un
     * borrado que nunca ocurre.
     */
    @Override
    public ResultadoAccion borrarUsuario(String usuarioId, String emailAdmin) {
        return cambiarAlta(Map.of("uid", usuarioId, "activo", "false"), emailAdmin);
    }

    @Override
    public List<Tabla> tablas() {
        if (!estado().disponible()) {
            return List.of();
        }

        List<Map<String, Object>> filas = repositorio.equipos().stream()
                .map(e -> {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("nombre", e.nombre());
                    fila.put("categoria", e.categoria());
                    fila.put("genero", e.genero());
                    fila.put("jugadores", e.jugadores());
                    fila.put("entrenadores", e.entrenadores());
                    fila.put("estado", e.archivado() ? "Archivado" : "En activo");
                    return fila;
                })
                .toList();

        return List.of(new Tabla(
                "equipos",
                "Equipos del club",
                List.of(
                        Tabla.Columna.texto("nombre", "Equipo"),
                        Tabla.Columna.texto("categoria", "Categoría"),
                        Tabla.Columna.texto("genero", "Género"),
                        Tabla.Columna.entero("jugadores", "Jugadores"),
                        Tabla.Columna.entero("entrenadores", "Entrenadores"),
                        Tabla.Columna.texto("estado", "Estado")),
                filas,
                "El club todavía no tiene equipos creados"));
    }

    // ------------------------------------------------- opciones y validación

    private List<Campo.Opcion> opciones(List<String> valores) {
        return valores.stream().map(v -> Campo.Opcion.de(v, v)).toList();
    }

    /** La lista con un «sin cambios» delante, para los formularios de edición. */
    private List<Campo.Opcion> conSinCambios(List<String> valores, String comoVaciar) {
        List<Campo.Opcion> lista = new ArrayList<>();
        lista.add(new Campo.Opcion("", "Dejarlo como está", null));
        if (comoVaciar != null) {
            lista.add(new Campo.Opcion(VACIAR, comoVaciar, null));
        }
        valores.forEach(v -> lista.add(Campo.Opcion.de(v, v)));
        return lista;
    }

    /** La lista con un «ninguno» delante, para los formularios de alta. */
    private List<Campo.Opcion> conVacio(List<String> valores, String etiquetaVacio) {
        List<Campo.Opcion> lista = new ArrayList<>();
        lista.add(new Campo.Opcion("", etiquetaVacio, null));
        valores.forEach(v -> lista.add(Campo.Opcion.de(v, v)));
        return lista;
    }

    private List<Campo.Opcion> opcionesDeRol() {
        return edicionRol().orElseThrow().opciones().stream()
                .map(o -> new Campo.Opcion(o.valor(), o.etiqueta(), o.detalle()))
                .toList();
    }

    /**
     * La gente del club para un desplegable.
     *
     * Salen también quienes están de baja, marcados. Una baja no borra a nadie, y
     * es normal querer corregirle el teléfono o meterla en un equipo el día que
     * vuelve; esconderla obligaría a reactivarla primero solo para poder verla.
     */
    private List<Campo.Opcion> opcionesDePersona(List<RepositorioCvo.Ficha> fichas) {
        return fichas.stream()
                .map(f -> new Campo.Opcion(
                        f.uid(),
                        f.nombre() == null || f.nombre().isBlank() ? f.email() : f.nombre(),
                        f.activo() ? f.email() : f.email() + " · de baja"))
                .toList();
    }

    private List<Campo.Opcion> opcionesDeEquipo(List<RepositorioCvo.Equipo> equipos, boolean conArchivados) {
        return equipos.stream()
                .filter(e -> conArchivados || !e.archivado())
                .map(e -> new Campo.Opcion(
                        e.id(),
                        e.nombre(),
                        e.archivado() ? "archivado" : e.categoria() + " · " + e.genero()))
                .toList();
    }

    /**
     * La temporada de ahora y la siguiente.
     *
     * La temporada arranca en septiembre: de enero a agosto todavía es la
     * anterior. Es el mismo cálculo que hace la app, y está aquí para que crear un
     * equipo no obligue a escribir «2026/27» a mano y acertar con el formato.
     */
    private List<String> temporadas() {
        LocalDate hoy = LocalDate.now();
        int inicio = hoy.getMonthValue() >= 9 ? hoy.getYear() : hoy.getYear() - 1;
        return List.of(temporada(inicio), temporada(inicio + 1), temporada(inicio - 1));
    }

    private String temporada(int inicio) {
        return inicio + "/" + String.format("%02d", (inicio + 1) % 100);
    }

    private Map<String, Object> exigirFicha(String uid) {
        if (uid.isBlank()) {
            throw new PeticionInvalida("Falta elegir a la persona");
        }
        Map<String, Object> ficha = repositorio.ficha(uid);
        if (ficha.isEmpty()) {
            throw new PeticionInvalida("Esa ficha no existe en el club");
        }
        return ficha;
    }

    private Map<String, Object> exigirEquipo(String id) {
        if (id.isBlank()) {
            throw new PeticionInvalida("Falta elegir el equipo");
        }
        Map<String, Object> equipo = repositorio.equipo(id);
        if (equipo.isEmpty()) {
            throw new PeticionInvalida("Ese equipo no existe");
        }
        return equipo;
    }

    private void exigirDeLaLista(String valor, List<String> validos, String queEs) {
        if (!validos.contains(valor)) {
            throw new PeticionInvalida("'" + valor + "' no es una " + queEs + " del club");
        }
    }

    /**
     * Que el papel en el equipo case con los roles de club.
     *
     * Es la comprobación que en la app hacen las reglas de Firestore y que aquí
     * hay que repetir a mano, porque el Admin SDK no pasa por ellas. Sin esto, un
     * jugador podría acabar en la lista de entrenadores de un equipo, que es
     * exactamente la lista de la que sale quién manda avisos.
     */
    private void exigirPapelCoherente(String papel, List<String> roles) {
        if (!"jugador".equals(papel) && !"entrenador".equals(papel)) {
            throw new PeticionInvalida("En un equipo se entra como jugador o como entrenador");
        }
        if ("entrenador".equals(papel) && !roles.contains("entrenador") && !roles.contains("admin")) {
            throw new PeticionInvalida(
                    "Para entrar como entrenador hace falta tener el rol de entrenador o de admin");
        }
        if ("jugador".equals(papel) && !roles.contains("jugador")) {
            throw new PeticionInvalida("Para entrar como jugador hace falta tener el rol de jugador");
        }
    }

    private String nombreDeEquipo(String id) {
        return repositorio.nombresDeEquipo().getOrDefault(id, id);
    }

    @SuppressWarnings("unchecked")
    private List<String> listaDe(Map<String, Object> documento, String campo) {
        if (documento.get(campo) instanceof List<?> lista) {
            return ((List<Object>) lista).stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** En blanco es «no lo toques»; el guion es «déjalo vacío». */
    private void ponerSiViene(Map<String, Object> cambios, String clave, String valor) {
        if (valor.isBlank()) {
            return;
        }
        cambios.put(clave, VACIAR.equals(valor) ? "" : valor);
    }

    /** Igual, pero un enlace vacío es {@code null} y no cadena vacía. */
    private void ponerEnlaceSiViene(Map<String, Object> cambios, String clave, String valor) {
        if (valor.isBlank()) {
            return;
        }
        cambios.put(clave, VACIAR.equals(valor) ? null : valor);
    }

    // ---------------------------------------------------------------- apoyo

    private String texto(Map<String, Object> parametros, String clave) {
        Object valor = parametros.get(clave);
        return valor == null ? "" : String.valueOf(valor).trim();
    }

    private boolean bandera(Map<String, Object> parametros, String clave) {
        Object valor = parametros.get(clave);
        return valor instanceof Boolean b ? b : Boolean.parseBoolean(texto(parametros, clave));
    }

    /**
     * Los roles que llegan del formulario.
     *
     * Un campo de varios valores llega como lista JSON, pero se admite también una
     * cadena separada por comas: la petición se puede montar a mano y no merece la
     * pena que falle por la forma. Lo que sí se exige es que sean roles de verdad
     * y que quede al menos uno —una ficha sin roles entra en la app y no puede
     * hacer nada, que es el peor de los estados posibles.
     */
    private List<String> roles(Map<String, Object> parametros) {
        Object bruto = parametros.get("roles");
        List<String> roles;

        if (bruto instanceof List<?> lista) {
            roles = lista.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).toList();
        } else {
            roles = java.util.Arrays.stream(texto(parametros, "roles").split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).toList();
        }

        if (roles.isEmpty()) {
            throw new PeticionInvalida("Hay que darle al menos un rol");
        }
        for (String rol : roles) {
            if (!ROLES.contains(rol)) {
                throw new PeticionInvalida("El rol '" + rol + "' no existe en el club");
            }
        }
        return roles.stream().distinct().toList();
    }

    /**
     * Contraseña fácil de dictar.
     *
     * Sin i/I/l/1/0/O ni caracteres raros. Esta contraseña se copia de una pantalla
     * a un papel o a un chat de WhatsApp, y ahí es donde se pierden: es el mismo
     * criterio que usa la app al dar de alta desde el móvil.
     */
    private String inventarClave() {
        final String alfabeto = "abcdefghjkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom azar = new SecureRandom();
        StringBuilder clave = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            clave.append(alfabeto.charAt(azar.nextInt(alfabeto.length())));
        }
        return clave.toString();
    }
}
