package com.bluedebug.gestion.conectores.cvo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Manda los avisos del club por el servicio de push de Expo.
 *
 * La app del club es Expo y sus tokens tienen la pinta
 * {@code ExponentPushToken[xxxxxxxx]}. No son tokens de FCM y no se les puede
 * hablar con firebase-admin: hay que pasar por el servidor de Expo, que es quien
 * sabe traducirlos a la notificación de Apple o de Google que toque.
 *
 * EXPO CONTESTA DOS VECES, Y ESO ES LO IMPORTANTE DE ESTA CLASE
 *
 * Al mandar, Expo devuelve un <em>ticket</em> por destinatario. Un ticket con
 * {@code status: ok} NO significa que la notificación haya llegado: significa
 * que Expo la ha aceptado y la ha puesto en cola. El resultado de verdad está en
 * el <em>recibo</em>, que se pide después con el id del ticket, y es ahí donde
 * aparecen los errores que explican por qué un móvil concreto no ha sonado:
 *
 *   · {@code DeviceNotRegistered} — la app ya no está instalada en ese aparato,
 *     o se reinstaló y el token viejo murió. Es el más habitual con diferencia.
 *   · {@code InvalidCredentials} — las credenciales de FCM o de APNs del proyecto
 *     de Expo no valen. Afecta a TODOS los envíos de esa plataforma.
 *   · {@code MessageTooBig}, {@code MessageRateExceeded} — lo que dicen.
 *
 * La primera versión de esta clase solo miraba los tickets y cantaba
 * «entregados: 6». Era mentira, y de la peor clase: la que hace perder la tarde
 * buscando el fallo en el móvil cuando el servidor ya sabía lo que había pasado y
 * no lo estaba preguntando.
 */
@Service
public class ServicioPushExpo {

    private static final Logger log = LoggerFactory.getLogger(ServicioPushExpo.class);

    /** Expo acepta como mucho cien mensajes por petición de envío. */
    private static final int TAMANO_LOTE = 100;

    /** Y hasta mil ids por petición de recibos. */
    private static final int LOTE_RECIBOS = 300;

    /**
     * Cuánto se espera antes de pedir los recibos.
     *
     * Expo dice que pueden tardar, y recomienda pedirlos pasados unos minutos.
     * En la práctica están listos en segundos, y este es un botón que alguien
     * está mirando: se esperan cuatro segundos, se pide lo que haya, y lo que
     * siga sin recibo se cuenta aparte como «sin confirmar» en vez de darlo por
     * bueno.
     */
    private static final long ESPERA_RECIBOS_MS = 4_000;

    /**
     * El canal de Android por el que entra el aviso.
     *
     * Esto NO es opcional y su ausencia fue un fallo real. La app declara cuatro
     * canales ({@code avisos}, {@code chat}, {@code calendario}, {@code club}) y
     * todos sus envíos dicen por cuál van. Un aviso del panel es de la misma
     * naturaleza que el de un entrenador, así que va por {@code avisos}, que es
     * el de importancia alta: suena y vibra. Sin declararlo, queda a merced del
     * canal por defecto del manifiesto.
     */
    private static final String CANAL = "avisos";

    private final RestClient http = RestClient.builder()
            .baseUrl("https://exp.host/--/api/v2/push")
            .defaultHeader("accept", "application/json")
            .defaultHeader("accept-encoding", "gzip, deflate")
            .build();

    /**
     * Lo que ha pasado de verdad con un envío.
     *
     * @param aceptados     los que Expo metió en cola.
     * @param confirmados   los que Expo confirma haber entregado al móvil.
     * @param fallidos      los que fallaron, en el ticket o en el recibo.
     * @param sinConfirmar  aceptados cuyo recibo aún no estaba listo. Ni buenos ni malos.
     * @param tokensMuertos tokens que hay que borrar de Firestore.
     * @param motivos       error → cuántas veces, para poder explicarlo en pantalla.
     */
    public record Envio(
            int aceptados,
            int confirmados,
            int fallidos,
            int sinConfirmar,
            List<String> tokensMuertos,
            Map<String, Integer> motivos
    ) {}

    public Envio enviar(List<String> tokens, String titulo, String cuerpo) {
        // token → id de ticket, para poder atribuir cada recibo a su aparato.
        Map<String, String> ticketDeToken = new LinkedHashMap<>();
        List<String> muertos = new ArrayList<>();
        Map<String, Integer> motivos = new TreeMap<>();
        int fallidos = 0;

        // --- primera vuelta: mandar y quedarse con los tickets ---------------
        for (int i = 0; i < tokens.size(); i += TAMANO_LOTE) {
            List<String> lote = tokens.subList(i, Math.min(tokens.size(), i + TAMANO_LOTE));

            List<Map<String, Object>> mensajes = lote.stream().map(token -> {
                Map<String, Object> mensaje = new LinkedHashMap<>();
                mensaje.put("to", token);
                mensaje.put("title", titulo);
                mensaje.put("body", cuerpo);
                mensaje.put("sound", "default");
                mensaje.put("channelId", CANAL);
                // Con prioridad normal, Android puede retrasar el aviso hasta que
                // el móvil salga del modo de ahorro. La app manda `high` en los
                // suyos y aquí se hace igual.
                mensaje.put("priority", "high");
                mensaje.put("data", Map.of("origen", "panel"));
                return mensaje;
            }).toList();

            List<?> tickets = pedir("/send", mensajes);
            if (tickets == null) {
                fallidos += lote.size();
                motivos.merge("Expo no respondió al envío", lote.size(), Integer::sum);
                continue;
            }

            for (int j = 0; j < tickets.size() && j < lote.size(); j++) {
                if (!(tickets.get(j) instanceof Map<?, ?> ticket)) {
                    fallidos++;
                    continue;
                }
                if ("ok".equals(ticket.get("status"))) {
                    Object id = ticket.get("id");
                    if (id != null) {
                        ticketDeToken.put(lote.get(j), String.valueOf(id));
                    }
                    continue;
                }

                fallidos++;
                String error = errorDe(ticket);
                motivos.merge(error, 1, Integer::sum);
                if ("DeviceNotRegistered".equals(error)) {
                    muertos.add(lote.get(j));
                }
            }
        }

        int aceptados = ticketDeToken.size();
        if (aceptados == 0) {
            return new Envio(0, 0, fallidos, 0, muertos, motivos);
        }

        // --- segunda vuelta: preguntar qué pasó de verdad --------------------
        try {
            Thread.sleep(ESPERA_RECIBOS_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Envio(aceptados, 0, fallidos, aceptados, muertos, motivos);
        }

        Map<String, Map<?, ?>> recibos = recibos(new ArrayList<>(ticketDeToken.values()));
        int confirmados = 0;
        int sinConfirmar = 0;

        for (Map.Entry<String, String> par : ticketDeToken.entrySet()) {
            Map<?, ?> recibo = recibos.get(par.getValue());
            if (recibo == null) {
                sinConfirmar++;
                continue;
            }
            if ("ok".equals(recibo.get("status"))) {
                confirmados++;
                continue;
            }

            fallidos++;
            String error = errorDe(recibo);
            motivos.merge(error, 1, Integer::sum);
            if ("DeviceNotRegistered".equals(error)) {
                muertos.add(par.getKey());
            }
        }

        if (!motivos.isEmpty()) {
            log.info("CVO push: aceptados={} confirmados={} fallidos={} motivos={}",
                    aceptados, confirmados, fallidos, motivos);
        }

        return new Envio(aceptados, confirmados, fallidos, sinConfirmar, muertos, motivos);
    }

    /** Pide los recibos de una tanda de tickets. */
    private Map<String, Map<?, ?>> recibos(List<String> ids) {
        Map<String, Map<?, ?>> todos = new LinkedHashMap<>();

        for (int i = 0; i < ids.size(); i += LOTE_RECIBOS) {
            List<String> lote = ids.subList(i, Math.min(ids.size(), i + LOTE_RECIBOS));
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> respuesta = http.post()
                        .uri("/getReceipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("ids", lote))
                        .retrieve()
                        .body(Map.class);

                if (respuesta != null && respuesta.get("data") instanceof Map<?, ?> datos) {
                    datos.forEach((clave, valor) -> {
                        if (valor instanceof Map<?, ?> recibo) {
                            todos.put(String.valueOf(clave), recibo);
                        }
                    });
                }
            } catch (Exception e) {
                // Sin recibos no se puede afirmar nada: esos envíos quedan como
                // «sin confirmar», que es la verdad.
                log.warn("CVO: no se pudieron leer los recibos de Expo: {}", e.getMessage());
            }
        }

        return todos;
    }

    /** Manda una petición y devuelve la lista de `data`, o null si algo falló. */
    private List<?> pedir(String ruta, Object cuerpo) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> respuesta = http.post()
                    .uri(ruta)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .body(Map.class);

            return respuesta != null && respuesta.get("data") instanceof List<?> datos ? datos : null;
        } catch (Exception e) {
            log.error("CVO: falló la llamada {} de Expo: {}", ruta, e.getMessage());
            return null;
        }
    }

    /** Saca el código de error de un ticket o de un recibo. */
    private String errorDe(Map<?, ?> nodo) {
        if (nodo.get("details") instanceof Map<?, ?> detalles && detalles.get("error") != null) {
            return String.valueOf(detalles.get("error"));
        }
        Object mensaje = nodo.get("message");
        return mensaje == null ? "error sin detalle" : String.valueOf(mensaje);
    }
}
