package com.bluedebug.gestion.conectores.cvo;

import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.auth.ExportedUserRecord;
import com.google.firebase.auth.ListUsersPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Las lecturas del club: Firestore para las fichas y Firebase Auth para saber
 * cuándo entró cada uno por última vez.
 *
 * POR QUÉ HACEN FALTA LAS DOS. En Firestore está quién es cada persona —nombre,
 * roles, equipos, si está de alta— pero no hay ni rastro de cuándo entró: la app
 * no lo escribe. Eso lo sabe Firebase Auth, que guarda el último acceso de cada
 * cuenta y lo da en {@code listUsers}. Se cruzan por uid, que es la misma clave
 * en los dos sitios.
 *
 * Y una cosa que conviene entender de este modelo, porque explica varias
 * consultas de aquí abajo: tener cuenta en Firebase Auth NO es ser del club.
 * Cualquiera puede registrarse; lo que da acceso a algo es tener ficha en
 * {@code usuarios/} con {@code activo: true}, y esas fichas solo las crea un
 * administrador. Por eso puede haber cuentas en Auth sin ficha, y son
 * exactamente eso: gente que se registró y se quedó a la puerta.
 */
@Repository
public class RepositorioCvo {

    private static final Logger log = LoggerFactory.getLogger(RepositorioCvo.class);

    private final FuenteCvo fuente;

    public RepositorioCvo(FuenteCvo fuente) {
        this.fuente = fuente;
    }

    // ---------------------------------------------------------------- usuarios

    public List<UsuarioApp> usuarios() {
        Map<String, Instant> accesos = ultimosAccesos();
        Map<String, String> nombresEquipo = nombresDeEquipo();

        List<UsuarioApp> gente = new ArrayList<>();
        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            gente.add(aUsuario(doc, accesos, nombresEquipo));
        }

        // Los más recientes arriba: sin último acceso, al final. Es el orden con el
        // que se mira una lista de socios cuando se busca «quién ha entrado hoy».
        gente.sort((a, b) -> {
            if (a.ultimaSesion() == null && b.ultimaSesion() == null) return 0;
            if (a.ultimaSesion() == null) return 1;
            if (b.ultimaSesion() == null) return -1;
            return b.ultimaSesion().compareTo(a.ultimaSesion());
        });

        return gente;
    }

    private UsuarioApp aUsuario(QueryDocumentSnapshot doc,
                                Map<String, Instant> accesos,
                                Map<String, String> nombresEquipo) {
        List<String> roles = roles(doc);
        List<String> equipos = lista(doc, "equipos");

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("roles", String.join(", ", roles));
        extra.put("equipos", equipos.stream()
                .map(id -> nombresEquipo.getOrDefault(id, id))
                .toList());
        extra.put("dorsal", doc.getString("dorsal"));
        extra.put("posicion", doc.getString("posicion"));
        extra.put("telefono", doc.getString("telefono"));

        boolean activo = Boolean.TRUE.equals(doc.getBoolean("activo"));

        return new UsuarioApp(
                doc.getId(),
                doc.getString("nombre"),
                doc.getString("email"),
                instante(doc.get("creadoEn")),
                accesos.get(doc.getId()),
                // El «plan» de un club es su papel. Se enseña el más alto que tenga,
                // que es lo que interesa de un vistazo en la tabla.
                rolPrincipal(roles),
                activo,
                lista(doc, "tokensPush").size(),
                extra);
    }

    /**
     * Los roles, admitiendo el formato viejo.
     *
     * La app guarda {@code roles} en plural, pero las fichas anteriores al cambio
     * —y la primera de admin, que se crea a mano en la consola— traen {@code rol}
     * en singular. La app lee las dos formas y aquí hay que hacer lo mismo: si no,
     * el panel enseñaría al administrador del club como si no tuviera rol ninguno.
     */
    private List<String> roles(DocumentSnapshot doc) {
        List<String> plural = lista(doc, "roles");
        if (!plural.isEmpty()) {
            return plural;
        }
        String singular = doc.getString("rol");
        return singular == null || singular.isBlank() ? List.of("jugador") : List.of(singular);
    }

    private String rolPrincipal(List<String> roles) {
        if (roles.contains("admin")) return "admin";
        if (roles.contains("entrenador")) return "entrenador";
        return roles.isEmpty() ? "jugador" : roles.get(0);
    }

    // ----------------------------------------------------------------- equipos

    /** id → nombre, para no enseñar identificadores en la tabla de socios. */
    public Map<String, String> nombresDeEquipo() {
        Map<String, String> nombres = new HashMap<>();
        for (QueryDocumentSnapshot doc : documentos("equipos")) {
            nombres.put(doc.getId(), doc.getString("nombre"));
        }
        return nombres;
    }

    public record Equipo(String id, String nombre, String categoria, String genero,
                         int jugadores, int entrenadores, boolean archivado) {}

    public List<Equipo> equipos() {
        List<Equipo> equipos = new ArrayList<>();
        for (QueryDocumentSnapshot doc : documentos("equipos")) {
            equipos.add(new Equipo(
                    doc.getId(),
                    doc.getString("nombre"),
                    doc.getString("categoria"),
                    doc.getString("genero"),
                    lista(doc, "jugadores").size(),
                    lista(doc, "entrenadores").size(),
                    Boolean.TRUE.equals(doc.getBoolean("archivado"))));
        }
        equipos.sort((a, b) -> String.valueOf(a.nombre()).compareToIgnoreCase(String.valueOf(b.nombre())));
        return equipos;
    }

    // ------------------------------------------------------------------ tokens

    /**
     * Los tokens de Expo a los que va dirigido un aviso.
     *
     * Se salta a quien esté de baja a propósito. Una baja en el club no borra la
     * cuenta ni sus tokens: si no se filtrara aquí, quien se fue en octubre
     * seguiría recibiendo los avisos del equipo en marzo.
     */
    public List<String> tokensPara(String destino) {
        List<String> tokens = new ArrayList<>();

        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            if (!Boolean.TRUE.equals(doc.getBoolean("activo"))) {
                continue;
            }
            boolean encaja = switch (destino) {
                case "todos" -> true;
                case "entrenadores" -> roles(doc).contains("entrenador") || roles(doc).contains("admin");
                case "jugadores" -> roles(doc).contains("jugador");
                case "admins" -> roles(doc).contains("admin");
                // Cualquier otra cosa se interpreta como el id de un equipo.
                default -> lista(doc, "equipos").contains(destino);
            };
            if (encaja) {
                tokens.addAll(lista(doc, "tokensPush"));
            }
        }

        return tokens.stream().distinct().toList();
    }

    // ------------------------------------------------------------------ series

    public Map<LocalDate, Double> altasPorDia(Rango rango) {
        Map<LocalDate, Double> porDia = new HashMap<>();
        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            Instant creado = instante(doc.get("creadoEn"));
            if (creado == null || creado.isBefore(rango.inicio()) || !creado.isBefore(rango.fin())) {
                continue;
            }
            porDia.merge(rango.diaDe(creado), 1d, Double::sum);
        }
        return porDia;
    }

    public Map<LocalDate, Double> accesosPorDia(Rango rango) {
        Map<LocalDate, Double> porDia = new HashMap<>();
        for (Map.Entry<String, Instant> entrada : ultimosAccesos().entrySet()) {
            Instant acceso = entrada.getValue();
            if (acceso == null || acceso.isBefore(rango.inicio()) || !acceso.isBefore(rango.fin())) {
                continue;
            }
            porDia.merge(rango.diaDe(acceso), 1d, Double::sum);
        }
        return porDia;
    }

    // -------------------------------------------------------------------- auth

    /**
     * uid → último acceso, según Firebase Auth.
     *
     * {@code listUsers} pagina de mil en mil. Un club son decenas de cuentas, así
     * que con una vuelta sobra, pero el bucle está escrito para paginar igual: el
     * día que crezca no hay que acordarse de esto.
     */
    public Map<String, Instant> ultimosAccesos() {
        Map<String, Instant> accesos = new HashMap<>();
        if (fuente.auth() == null) {
            return accesos;
        }
        try {
            ListUsersPage pagina = fuente.auth().listUsers(null);
            while (pagina != null) {
                for (ExportedUserRecord cuenta : pagina.getValues()) {
                    long ultimo = cuenta.getUserMetadata().getLastSignInTimestamp();
                    accesos.put(cuenta.getUid(), ultimo > 0 ? Instant.ofEpochMilli(ultimo) : null);
                }
                pagina = pagina.getNextPage();
            }
        } catch (Exception e) {
            // Sin esto la tabla sale sin la columna de último acceso, que es una pena
            // pero no impide administrar nada. No se propaga.
            log.warn("CVO: no se pudieron leer los accesos de Firebase Auth: {}", e.getMessage());
        }
        return accesos;
    }

    /** Cuentas registradas en Auth que no tienen ficha en el club. */
    public int cuentasSinFicha() {
        if (fuente.auth() == null) {
            return 0;
        }
        java.util.Set<String> conFicha = new java.util.HashSet<>();
        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            conFicha.add(doc.getId());
        }
        return (int) ultimosAccesos().keySet().stream().filter(uid -> !conFicha.contains(uid)).count();
    }

    // ------------------------------------------------------------- escrituras

    /**
     * Da de baja o vuelve a dar de alta a alguien.
     *
     * Nunca se borra el documento. Es la forma en la que el club entiende una
     * baja: la persona deja de tener acceso —las reglas de Firestore miran
     * {@code activo}— pero su histórico, sus mensajes y su paso por los equipos
     * siguen ahí. Borrarla dejaría chats firmados por un fantasma.
     */
    public void cambiarAlta(String uid, boolean activo) throws ExecutionException, InterruptedException {
        fuente.firestore().collection("usuarios").document(uid)
                .update("activo", activo)
                .get();
    }

    public Map<String, Object> ficha(String uid) {
        try {
            DocumentSnapshot doc = fuente.firestore().collection("usuarios").document(uid).get().get();
            if (!doc.exists()) {
                return Map.of();
            }
            Map<String, Object> datos = new LinkedHashMap<>(doc.getData() == null ? Map.of() : doc.getData());
            datos.put("uid", doc.getId());
            return datos;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            log.warn("CVO: no se pudo leer la ficha {}: {}", uid, e.getMessage());
            return Map.of();
        }
    }

    // ------------------------------------------------------------------- apoyo

    private List<QueryDocumentSnapshot> documentos(String coleccion) {
        try {
            return fuente.firestore().collection(coleccion).get().get().getDocuments();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("CVO: no se pudo leer la colección {}: {}", coleccion, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> lista(DocumentSnapshot doc, String campo) {
        Object valor = doc.get(campo);
        if (!(valor instanceof List<?> bruta)) {
            return List.of();
        }
        return ((List<Object>) bruta).stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private Instant instante(Object valor) {
        if (valor instanceof Timestamp t) {
            return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
        }
        if (valor instanceof java.util.Date d) {
            return d.toInstant();
        }
        return null;
    }
}
