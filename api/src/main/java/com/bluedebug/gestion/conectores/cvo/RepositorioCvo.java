package com.bluedebug.gestion.conectores.cvo;

import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.google.firebase.auth.ExportedUserRecord;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.ListUsersPage;
import com.google.firebase.auth.UserRecord;
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
        // La lista en crudo, no un texto ya montado: la tabla la pinta igual
        // uniéndola por comas, y el editor de roles necesita poder marcar cuáles
        // están puestos. Un texto habría que volver a partirlo, y eso es la clase
        // de ida y vuelta que acaba fallando con el primer rol que lleve una coma.
        extra.put("roles", roles);
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

    /**
     * Borra de las fichas los tokens que Expo da por muertos.
     *
     * Hace falta de verdad, no es limpieza cosmética. La app añade el token con
     * {@code arrayUnion} cada vez que alguien entra, y nadie quita los viejos:
     * cada reinstalación deja uno más colgando. Se ven fichas con seis tokens de
     * un solo móvil, de los cuales cinco están muertos. Eso infla el recuento de
     * «móviles con la app», hace que cada envío gaste intentos en aparatos que ya
     * no existen, y —lo peor— disfraza el resultado: un envío que llega a una
     * persona sale como «uno de seis», que parece un fallo y no lo es.
     *
     * @return cuántos se han borrado.
     */
    public int limpiarTokens(List<String> muertos) {
        if (muertos == null || muertos.isEmpty() || fuente.firestore() == null) {
            return 0;
        }

        java.util.Set<String> aBorrar = new java.util.HashSet<>(muertos);
        int borrados = 0;

        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            List<String> suyos = lista(doc, "tokensPush").stream().filter(aBorrar::contains).toList();
            if (suyos.isEmpty()) {
                continue;
            }
            try {
                doc.getReference()
                        .update("tokensPush", FieldValue.arrayRemove(suyos.toArray()))
                        .get();
                borrados += suyos.size();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return borrados;
            } catch (Exception e) {
                log.warn("CVO: no se pudieron limpiar los tokens de {}: {}", doc.getId(), e.getMessage());
            }
        }

        return borrados;
    }

    /**
     * Escribe los roles de una ficha.
     *
     * Además de guardar `roles`, BORRA el campo `rol` en singular si lo hubiera.
     * Las fichas antiguas —y la primera de admin, la que se crea a mano en la
     * consola— lo traen, y la app admite los dos formatos leyendo primero el
     * plural. Dejar el viejo ahí sería guardar dos verdades sobre la misma
     * persona: funcionaría hasta que algo leyera el equivocado.
     */
    public void cambiarRoles(String uid, List<String> roles) throws ExecutionException, InterruptedException {
        fuente.firestore().collection("usuarios").document(uid)
                .update(Map.of(
                        "roles", roles,
                        "rol", FieldValue.delete()))
                .get();
    }

    /** Cuántas fichas activas tienen rol de administrador del club. */
    public long cuantosAdmins() {
        long total = 0;
        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            if (Boolean.TRUE.equals(doc.getBoolean("activo")) && roles(doc).contains("admin")) {
                total++;
            }
        }
        return total;
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

    /**
     * Lo mínimo de cada ficha para poder elegirla en un desplegable.
     *
     * Existe aparte de {@link #usuarios()} porque los formularios de las acciones
     * se construyen en cada carga de la pantalla, y {@code usuarios()} arrastra
     * una vuelta entera por Firebase Auth para sacar los últimos accesos. Para
     * pintar «Nombre — correo» eso no hace falta.
     */
    public record Ficha(String uid, String nombre, String email, boolean activo) {}

    public List<Ficha> fichas() {
        List<Ficha> fichas = new ArrayList<>();
        for (QueryDocumentSnapshot doc : documentos("usuarios")) {
            fichas.add(new Ficha(
                    doc.getId(),
                    doc.getString("nombre"),
                    doc.getString("email"),
                    Boolean.TRUE.equals(doc.getBoolean("activo"))));
        }
        fichas.sort((a, b) -> String.valueOf(a.nombre()).compareToIgnoreCase(String.valueOf(b.nombre())));
        return fichas;
    }

    /** Si ya hay una ficha en el club con ese correo. */
    public boolean emailConFicha(String email) {
        String buscado = email.trim().toLowerCase();
        return documentos("usuarios").stream()
                .anyMatch(d -> buscado.equalsIgnoreCase(String.valueOf(d.getString("email"))));
    }

    // --------------------------------------------------------- altas y fichas

    /**
     * El uid de una cuenta de Firebase Auth por su correo, o null si no existe.
     *
     * Se pregunta ANTES de crear nada porque hay un caso que pasa de verdad en el
     * club: alguien se registró por su cuenta, se quedó sin ficha —«registrados
     * sin ficha», la cifra del resumen— y meses después un admin le da el alta.
     * Esa persona ya tiene cuenta, y crearle otra con el mismo correo es
     * imposible: hay que darle la ficha que le falta y dejarle su contraseña.
     */
    public String uidPorEmail(String email) {
        if (fuente.auth() == null) {
            return null;
        }
        try {
            return fuente.auth().getUserByEmail(email).getUid();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean tieneFicha(String uid) {
        return !ficha(uid).isEmpty();
    }

    /** Crea la cuenta de Auth y devuelve su uid. */
    public String crearCuenta(String nombre, String email, String clave) throws FirebaseAuthException {
        UserRecord.CreateRequest peticion = new UserRecord.CreateRequest()
                .setEmail(email)
                .setPassword(clave)
                .setDisplayName(nombre)
                .setEmailVerified(false)
                .setDisabled(false);
        return fuente.auth().createUser(peticion).getUid();
    }

    /**
     * Borra una cuenta de Auth.
     *
     * Solo se usa para deshacer un alta a medias: si la cuenta se crea y luego
     * falla la ficha, la persona se queda con credenciales que no abren nada y el
     * correo ocupado para siempre. Una baja de club NO pasa por aquí: eso es
     * {@link #cambiarAlta}, que conserva el histórico.
     */
    public void borrarCuenta(String uid) throws FirebaseAuthException {
        fuente.auth().deleteUser(uid);
    }

    public void cambiarClave(String uid, String clave) throws FirebaseAuthException {
        fuente.auth().updateUser(new UserRecord.UpdateRequest(uid).setPassword(clave));
    }

    /** Escribe la ficha del club. La cuenta de Auth tiene que existir ya. */
    public void crearFicha(String uid, Map<String, Object> datos)
            throws ExecutionException, InterruptedException {
        Map<String, Object> ficha = new LinkedHashMap<>(datos);
        ficha.put("activo", true);
        ficha.put("tokensPush", List.of());
        ficha.put("creadoEn", FieldValue.serverTimestamp());
        fuente.firestore().collection("usuarios").document(uid).set(ficha).get();
    }

    public void actualizarFicha(String uid, Map<String, Object> cambios)
            throws ExecutionException, InterruptedException {
        fuente.firestore().collection("usuarios").document(uid).update(cambios).get();
    }

    // ----------------------------------------------------------- escribir equipos

    /** Crea un equipo vacío y devuelve su id. */
    public String crearEquipo(Map<String, Object> datos) throws ExecutionException, InterruptedException {
        Map<String, Object> equipo = new LinkedHashMap<>(datos);
        // Las dos plantillas nacen vacías y el enlace con la web se hace después,
        // a mano, desde la ficha del equipo: ver `slugWeb` en el modelo de la app.
        equipo.put("entrenadores", List.of());
        equipo.put("jugadores", List.of());
        equipo.put("archivado", false);
        equipo.put("creadoEn", FieldValue.serverTimestamp());
        return fuente.firestore().collection("equipos").add(equipo).get().getId();
    }

    public void actualizarEquipo(String id, Map<String, Object> cambios)
            throws ExecutionException, InterruptedException {
        fuente.firestore().collection("equipos").document(id).update(cambios).get();
    }

    public Map<String, Object> equipo(String id) {
        try {
            DocumentSnapshot doc = fuente.firestore().collection("equipos").document(id).get().get();
            if (!doc.exists()) {
                return Map.of();
            }
            Map<String, Object> datos = new LinkedHashMap<>(doc.getData() == null ? Map.of() : doc.getData());
            datos.put("id", doc.getId());
            return datos;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            log.warn("CVO: no se pudo leer el equipo {}: {}", id, e.getMessage());
            return Map.of();
        }
    }

    // --------------------------------------------------------------- plantillas

    /**
     * Mete a alguien en un equipo, por los dos lados y de una vez.
     *
     * La pertenencia vive por duplicado a propósito —en el equipo y en la ficha—
     * porque las reglas de Firestore necesitan la lista del equipo para decidir
     * sin leer otro documento, y la app necesita la de la ficha para saber a qué
     * equipos entrar. Las dos caras se escriben en el MISMO lote: si se hicieran
     * por separado y fallara la segunda, quedaría un jugador que el equipo cree
     * tener pero al que las reglas le niegan el chat.
     */
    public void anadirAEquipo(String equipoId, String uid, boolean comoEntrenador)
            throws ExecutionException, InterruptedException {
        WriteBatch lote = fuente.firestore().batch();
        lote.update(fuente.firestore().collection("equipos").document(equipoId),
                comoEntrenador ? "entrenadores" : "jugadores", FieldValue.arrayUnion(uid));
        lote.update(fuente.firestore().collection("usuarios").document(uid),
                "equipos", FieldValue.arrayUnion(equipoId));
        lote.commit().get();
    }

    public void quitarDeEquipo(String equipoId, String uid)
            throws ExecutionException, InterruptedException {
        WriteBatch lote = fuente.firestore().batch();
        // Se quita de las dos listas sin mirar en cuál estaba: `arrayRemove` con un
        // valor que no está no hace nada, y así no hace falta leer el equipo antes.
        lote.update(fuente.firestore().collection("equipos").document(equipoId), Map.of(
                "entrenadores", FieldValue.arrayRemove(uid),
                "jugadores", FieldValue.arrayRemove(uid)));
        lote.update(fuente.firestore().collection("usuarios").document(uid),
                "equipos", FieldValue.arrayRemove(equipoId));
        lote.commit().get();
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
