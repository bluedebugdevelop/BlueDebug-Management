package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.conectores.modelo.Ingresos;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * El dinero que entra por la App Store.
 *
 * VBStats cobra por dos sitios y hasta ahora el panel solo veía uno. El de Apple
 * no estaba por una razón de fondo: en la base de datos NO HAY historial de
 * cobros de Apple. Solo se guarda el último estado en la fila del usuario
 * ({@code apple_original_transaction_id} y poco más), así que el importe hay que
 * ir a buscarlo a Apple.
 *
 * QUÉ CIFRA DA ESTA CLASE, que es lo que hay que tener claro para no sumar peras
 * con manzanas: el PRECIO QUE PAGÓ EL CLIENTE, antes de la comisión del 15-30 %
 * que se queda Apple. Es a propósito, porque es exactamente el mismo criterio con
 * el que ya se cuenta Stripe —el importe del cargo, antes de las comisiones de
 * Stripe—, y solo así las dos cifras se pueden sumar y significar algo.
 *
 * Si algún día hace falta el dinero que de verdad llega al banco, eso es otra
 * integración distinta: los Informes de Ventas de App Store Connect, que dan el
 * neto ya descontado pero agregado por día, sin poder atribuirlo a personas.
 *
 * Sobre la firma de las transacciones: Apple las devuelve como JWS firmados y
 * aquí se decodifican sin verificar la firma. No es un descuido. La verificación
 * criptográfica importa cuando el dato llega por un camino que no controlas —una
 * notificación de servidor que entra por un webhook, donde cualquiera puede
 * publicar—; aquí lo pedimos nosotros, por TLS, a un endpoint autenticado de
 * Apple con nuestra propia clave. El canal ya es la garantía.
 */
@Service
public class ServicioAppStore {

    private static final Logger log = LoggerFactory.getLogger(ServicioAppStore.class);

    private static final String PRODUCCION = "https://api.storekit.itunes.apple.com";
    private static final String PRUEBAS = "https://api.storekit-sandbox.itunes.apple.com";

    /** Apple no acepta tokens de más de una hora. Se renueva con margen. */
    private static final long VIDA_TOKEN_MIN = 50;

    /** Tope de páginas por cuenta, por si un historial fuera larguísimo. */
    private static final int PAGINAS_MAXIMAS = 20;

    private final PropiedadesVbstats propiedades;
    private final ObjectMapper json;
    private final RestClient http;

    private String token;
    private Instant tokenCaduca = Instant.EPOCH;

    public ServicioAppStore(PropiedadesVbstats propiedades, ObjectMapper json) {
        this.propiedades = propiedades;
        this.json = json;
        this.http = RestClient.builder()
                .baseUrl(propiedades.applePruebas() ? PRUEBAS : PRODUCCION)
                .build();
    }

    public boolean configurado() {
        return propiedades.hayAppStore();
    }

    /** Una cuenta de VBStats que compró por Apple. */
    public record CuentaApple(int usuarioId, String email, String transaccionOriginal) {}

    /**
     * @param facturado   suma de lo cobrado en el periodo, en euros.
     * @param devuelto    lo revocado (devoluciones de Apple), en positivo.
     * @param movimientos cada cobro, con su fecha y su correo.
     * @param sinPrecio   transacciones que Apple devolvió sin importe. Ver abajo.
     * @param otraMoneda  transacciones en una moneda distinta del euro.
     */
    public record Cobros(
            double facturado,
            double devuelto,
            List<Ingresos.Movimiento> movimientos,
            int sinPrecio,
            int otraMoneda
    ) {
        static Cobros vacio() {
            return new Cobros(0, 0, List.of(), 0, 0);
        }
    }

    @Cacheable(value = "appstore", key = "#rango.desde() + '|' + #rango.hasta() + '|' + #cuentas.size()")
    public Cobros cobros(Rango rango, List<CuentaApple> cuentas) {
        if (!configurado() || cuentas.isEmpty()) {
            return Cobros.vacio();
        }

        List<Ingresos.Movimiento> movimientos = new ArrayList<>();
        double facturado = 0;
        double devuelto = 0;
        int sinPrecio = 0;
        int otraMoneda = 0;

        for (CuentaApple cuenta : cuentas) {
            for (JsonNode t : historial(cuenta.transaccionOriginal())) {
                Instant compra = Instant.ofEpochMilli(t.path("purchaseDate").asLong());
                if (compra.isBefore(rango.inicio()) || !compra.isBefore(rango.fin())) {
                    continue;
                }

                // `price` llega en milésimas de la unidad monetaria: 4990 son 4,99.
                // Apple lo incorporó a las transacciones en 2023; las compras más
                // antiguas pueden venir sin él, y en ese caso NO se inventa un
                // importe: se cuentan aparte para que el total no mienta por lo bajo
                // sin avisar.
                if (!t.hasNonNull("price")) {
                    sinPrecio++;
                    movimientos.add(movimiento(t, cuenta, 0, "sin importe"));
                    continue;
                }

                String moneda = t.path("currency").asText("EUR");
                if (!"EUR".equalsIgnoreCase(moneda)) {
                    // Sumar monedas distintas sin convertirlas daría un número sin
                    // sentido. Se enseña la línea, pero no entra en el total.
                    otraMoneda++;
                    movimientos.add(movimiento(t, cuenta, t.path("price").asDouble() / 1000d, "otra moneda"));
                    continue;
                }

                double importe = t.path("price").asDouble() / 1000d;
                boolean revocada = t.hasNonNull("revocationDate");

                if (revocada) {
                    devuelto += importe;
                    movimientos.add(movimiento(t, cuenta, -importe, "devuelto"));
                } else {
                    facturado += importe;
                    movimientos.add(movimiento(t, cuenta, importe, "pagado"));
                }
            }
        }

        movimientos.sort((a, b) -> b.fecha().compareTo(a.fecha()));

        if (sinPrecio > 0 || otraMoneda > 0) {
            log.info("App Store: {} transacciones sin importe y {} en otra moneda, fuera del total",
                    sinPrecio, otraMoneda);
        }

        return new Cobros(redondear(facturado), redondear(devuelto), movimientos, sinPrecio, otraMoneda);
    }

    private Ingresos.Movimiento movimiento(JsonNode t, CuentaApple cuenta, double importe, String estado) {
        return new Ingresos.Movimiento(
                t.path("transactionId").asText(),
                Instant.ofEpochMilli(t.path("purchaseDate").asLong()),
                cuenta.email(),
                redondear(importe),
                estado,
                "apple");
    }

    /** El historial de compras de una suscripción, ya decodificado. */
    private List<JsonNode> historial(String transaccionOriginal) {
        List<JsonNode> transacciones = new ArrayList<>();
        String revision = null;

        try {
            for (int pagina = 0; pagina < PAGINAS_MAXIMAS; pagina++) {
                String ruta = "/inApps/v2/history/" + transaccionOriginal
                        + "?sort=DESCENDING" + (revision == null ? "" : "&revision=" + revision);

                JsonNode respuesta = http.get()
                        .uri(ruta)
                        .header("Authorization", "Bearer " + token())
                        .retrieve()
                        .body(JsonNode.class);

                if (respuesta == null) {
                    break;
                }
                for (JsonNode firmada : respuesta.path("signedTransactions")) {
                    JsonNode carga = descodificar(firmada.asText());
                    if (carga != null) {
                        transacciones.add(carga);
                    }
                }
                if (!respuesta.path("hasMore").asBoolean(false)) {
                    break;
                }
                revision = respuesta.path("revision").asText(null);
                if (revision == null) {
                    break;
                }
            }
        } catch (Exception e) {
            // Una cuenta que falla no puede tumbar el resto del informe: lo peor
            // que pasa es que su dinero no salga, y eso se ve en el log.
            log.warn("App Store: no se pudo leer el historial de {}: {}",
                    transaccionOriginal, e.getMessage());
        }

        return transacciones;
    }

    /** Saca el JSON del medio de un JWS. Ver el comentario de cabecera sobre la firma. */
    private JsonNode descodificar(String jws) {
        try {
            String[] partes = jws.split("\\.");
            if (partes.length < 2) {
                return null;
            }
            return json.readTree(Base64.getUrlDecoder().decode(partes[1]));
        } catch (Exception e) {
            log.warn("App Store: transacción ilegible: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ token

    /**
     * El token de acceso, firmado con la clave .p8.
     *
     * Se guarda hasta que caduca en vez de firmar uno por llamada: con varias
     * cuentas y varias páginas cada una, firmar cada vez sería trabajo de más
     * para nada.
     */
    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenCaduca)) {
            return token;
        }

        Instant ahora = Instant.now();
        Instant caduca = ahora.plusSeconds(VIDA_TOKEN_MIN * 60);

        String cabecera = b64(String.format(
                "{\"alg\":\"ES256\",\"kid\":\"%s\",\"typ\":\"JWT\"}", propiedades.appleKeyId()));
        String cuerpo = b64(String.format(
                "{\"iss\":\"%s\",\"iat\":%d,\"exp\":%d,\"aud\":\"appstoreconnect-v1\",\"bid\":\"%s\"}",
                propiedades.appleIssuerId(), ahora.getEpochSecond(), caduca.getEpochSecond(),
                propiedades.appleBundleId()));

        // P1363 y no DER: un JWT ES256 quiere la firma como R||S en crudo, y
        // `SHA256withECDSA` a secas la devuelve envuelta en DER. Apple rechaza esa
        // con un 401 que no explica nada. Java 11 en adelante trae esta variante
        // justo para esto.
        Signature firma = Signature.getInstance("SHA256withECDSAinP1363Format");
        firma.initSign(clavePrivada());
        firma.update((cabecera + "." + cuerpo).getBytes(StandardCharsets.UTF_8));

        token = cabecera + "." + cuerpo + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(firma.sign());
        // Se caduca antes que en el propio token, para no usarlo justo al filo.
        tokenCaduca = caduca.minusSeconds(120);

        return token;
    }

    private PrivateKey clavePrivada() throws Exception {
        String pem = propiedades.appleClave().trim();

        // Se admite tal cual (con sus cabeceras PEM) o en base64 de una línea, que
        // es como se acaba pegando en las variables de un servicio.
        if (!pem.contains("BEGIN")) {
            pem = new String(Base64.getDecoder().decode(pem), StandardCharsets.UTF_8);
        }

        String limpia = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(limpia)));
    }

    private String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private double redondear(double valor) {
        return Math.round(valor * 100d) / 100d;
    }

    /** Para la pantalla de ajustes: contra qué entorno se está hablando. */
    public Map<String, Object> resumenConfiguracion() {
        return Map.of(
                "configurado", configurado(),
                "entorno", propiedades.applePruebas() ? "sandbox" : "producción");
    }
}
