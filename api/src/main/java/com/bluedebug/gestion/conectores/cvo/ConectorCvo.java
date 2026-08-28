package com.bluedebug.gestion.conectores.cvo;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.modelo.AccionAdmin;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
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

/**
 * Club Voleibol Oviedo: la app del club.
 *
 * Es un caso distinto de VBStats y por eso el conector no se parece: aquí no hay
 * dinero ni planes, hay socios, equipos y roles. Y las bajas no son borrados:
 * quien deja el club se marca como inactivo y conserva su histórico, así que la
 * acción destructiva del panel no es «borrar», es «dar de baja» —y tiene vuelta
 * atrás.
 */
@Component
public class ConectorCvo implements ConectorApp {

    private static final Logger log = LoggerFactory.getLogger(ConectorCvo.class);

    private static final int DIAS_ACTIVIDAD = 30;

    private static final String ENVIAR_AVISO = "enviar-aviso";
    private static final String CAMBIAR_ALTA = "cambiar-alta";

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
                        DescriptorApp.Capacidad.ACCIONES),
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
        // gente sin atender.
        metricas.add(Metrica.entero("sinficha", "Registrados sin ficha", repositorio.cuentasSinFicha(),
                "esperando el alta de un admin"));

        List<Serie> series = List.of(
                rango.rellenar("altas", "Altas", "entero", repositorio.altasPorDia(rango)),
                rango.rellenar("accesos", "Cuentas con su último acceso ese día", "entero",
                        repositorio.accesosPorDia(rango)));

        Map<String, Long> porRol = new java.util.LinkedHashMap<>();
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

    @Override
    public List<AccionAdmin> acciones() {
        // Las opciones de destino se calculan al vuelo con los equipos que existen
        // ahora mismo. Es la diferencia entre un desplegable que sirve y uno que hay
        // que acordarse de actualizar cada temporada.
        List<AccionAdmin.Campo.Opcion> destinos = new ArrayList<>(List.of(
                new AccionAdmin.Campo.Opcion("todos", "Todo el club", null),
                new AccionAdmin.Campo.Opcion("jugadores", "Solo jugadores", null),
                new AccionAdmin.Campo.Opcion("entrenadores", "Entrenadores y admins", null),
                new AccionAdmin.Campo.Opcion("admins", "Solo administradores", null)));

        if (estado().disponible()) {
            repositorio.equipos().stream()
                    .filter(e -> !e.archivado())
                    .forEach(e -> destinos.add(new AccionAdmin.Campo.Opcion(
                            e.id(), e.nombre(), e.jugadores() + " jugadores")));
        }

        return List.of(new AccionAdmin(
                ENVIAR_AVISO,
                "Enviar aviso",
                "Manda una notificación a los móviles del club. Se salta a quien esté de baja.",
                "campana",
                false,
                "Enviar",
                List.of(
                        AccionAdmin.Campo.texto("titulo", "Título", 100, null),
                        AccionAdmin.Campo.area("cuerpo", "Mensaje", 400, null),
                        AccionAdmin.Campo.seleccion("destino", "A quién",
                                "Los equipos salen de Firestore, así que la lista está siempre al día.",
                                destinos))));
    }

    @Override
    public ResultadoAccion ejecutar(String accionId, Map<String, Object> parametros, String emailAdmin) {
        return switch (accionId) {
            case ENVIAR_AVISO -> enviarAviso(parametros, emailAdmin);
            case CAMBIAR_ALTA -> cambiarAlta(parametros, emailAdmin);
            default -> ResultadoAccion.error("CVO no conoce la acción '" + accionId + "'");
        };
    }

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
    public List<com.bluedebug.gestion.conectores.modelo.Tabla> tablas() {
        if (!estado().disponible()) {
            return List.of();
        }

        List<Map<String, Object>> filas = repositorio.equipos().stream()
                .map(e -> {
                    Map<String, Object> fila = new java.util.LinkedHashMap<>();
                    fila.put("nombre", e.nombre());
                    fila.put("categoria", e.categoria());
                    fila.put("genero", e.genero());
                    fila.put("jugadores", e.jugadores());
                    fila.put("entrenadores", e.entrenadores());
                    fila.put("estado", e.archivado() ? "Archivado" : "En activo");
                    return fila;
                })
                .toList();

        return List.of(new com.bluedebug.gestion.conectores.modelo.Tabla(
                "equipos",
                "Equipos del club",
                List.of(
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("nombre", "Equipo"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("categoria", "Categoría"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("genero", "Género"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.entero("jugadores", "Jugadores"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.entero("entrenadores", "Entrenadores"),
                        com.bluedebug.gestion.conectores.modelo.Tabla.Columna.texto("estado", "Estado")),
                filas,
                "El club todavía no tiene equipos creados"));
    }

    private String texto(Map<String, Object> parametros, String clave) {
        Object valor = parametros.get(clave);
        return valor == null ? "" : String.valueOf(valor).trim();
    }
}
