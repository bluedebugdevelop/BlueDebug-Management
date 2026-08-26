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

/**
 * Manda los avisos del club por el servicio de push de Expo.
 *
 * La app del club es Expo y sus tokens tienen la pinta
 * {@code ExponentPushToken[xxxxxxxx]}. No son tokens de FCM y no se les puede
 * hablar con firebase-admin: hay que pasar por el servidor de Expo, que es quien
 * sabe traducirlos a la notificación de Apple o de Google que toque.
 *
 * Esto es exactamente lo que hace la app por dentro cuando un entrenador manda un
 * aviso —llama a Expo desde el móvil, sin servidor de por medio—, así que el
 * mensaje que sale de aquí le llega a la gente igual que los suyos.
 *
 * No hace falta credencial ninguna: el token de destino ES la autorización. Por
 * eso importa que la lista de tokens no salga nunca de aquí hacia el navegador.
 */
@Service
public class ServicioPushExpo {

    private static final Logger log = LoggerFactory.getLogger(ServicioPushExpo.class);

    private static final String URL = "https://exp.host/--/api/v2/push/send";

    /** Expo acepta como mucho cien mensajes por petición. */
    private static final int TAMANO_LOTE = 100;

    private final RestClient http = RestClient.builder()
            .baseUrl(URL)
            .defaultHeader("accept", "application/json")
            // Expo comprime la respuesta si se le pide; con lotes de cien tickets la
            // diferencia se nota y no cuesta nada.
            .defaultHeader("accept-encoding", "gzip, deflate")
            .build();

    /**
     * @param entregados  tickets que Expo aceptó.
     * @param fallidos    los que rechazó.
     * @param tokensMuertos tokens que Expo marca como no registrados, para limpiarlos.
     */
    public record Envio(int entregados, int fallidos, List<String> tokensMuertos) {}

    public Envio enviar(List<String> tokens, String titulo, String cuerpo) {
        int entregados = 0;
        int fallidos = 0;
        List<String> muertos = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i += TAMANO_LOTE) {
            List<String> lote = tokens.subList(i, Math.min(tokens.size(), i + TAMANO_LOTE));

            List<Map<String, Object>> mensajes = lote.stream().map(token -> {
                Map<String, Object> mensaje = new LinkedHashMap<>();
                mensaje.put("to", token);
                mensaje.put("title", titulo);
                mensaje.put("body", cuerpo);
                mensaje.put("sound", "default");
                mensaje.put("data", Map.of("origen", "panel"));
                return mensaje;
            }).toList();

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> respuesta = http.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mensajes)
                        .retrieve()
                        .body(Map.class);

                if (respuesta == null || !(respuesta.get("data") instanceof List<?> tickets)) {
                    fallidos += lote.size();
                    continue;
                }

                for (int j = 0; j < tickets.size(); j++) {
                    if (!(tickets.get(j) instanceof Map<?, ?> ticket)) {
                        fallidos++;
                        continue;
                    }
                    if ("ok".equals(ticket.get("status"))) {
                        entregados++;
                        continue;
                    }
                    fallidos++;
                    // DeviceNotRegistered es «esa app ya no está instalada». Es el único
                    // error por el que merece la pena borrar el token: los demás
                    // (Expo saturado, mensaje mal formado) son cosa nuestra o pasajeros.
                    if (ticket.get("details") instanceof Map<?, ?> detalles
                            && "DeviceNotRegistered".equals(detalles.get("error"))
                            && j < lote.size()) {
                        muertos.add(lote.get(j));
                    }
                }
            } catch (Exception e) {
                log.error("CVO: falló un lote de avisos de Expo: {}", e.getMessage());
                fallidos += lote.size();
            }
        }

        return new Envio(entregados, fallidos, muertos);
    }
}
