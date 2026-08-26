package com.bluedebug.gestion.conectores.vbstats;

import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Reparto;
import com.bluedebug.gestion.conectores.modelo.Serie;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.param.ChargeListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El dinero que entra por VBStats, leído de Stripe.
 *
 * DOS AVISOS que hay que tener presentes al mirar estas cifras:
 *
 *   1. Aquí NO está lo que se cobra por la App Store. Las compras dentro de la
 *      app de iPhone las cobra Apple y no pasan por Stripe; en la base de datos
 *      se ven como suscripciones con {@code apple_original_transaction_id}, y el
 *      panel las cuenta como suscriptores, pero su importe no aparece en esta
 *      pantalla. Para tenerlo habría que enchufar App Store Connect, que es otra
 *      integración entera.
 *   2. Stripe habla en céntimos. Todo lo que sale de esta clase va ya en euros:
 *      la división se hace una vez, aquí, y no se repite en ningún otro sitio.
 *      Mezclar las dos unidades es la forma más rápida de enseñar una facturación
 *      cien veces mayor de la real.
 *
 * La clave que se le pone puede (y debería) ser una clave RESTRINGIDA de solo
 * lectura: el panel lee cobros, nunca cobra.
 */
@Service
public class ServicioStripe {

    private static final Logger log = LoggerFactory.getLogger(ServicioStripe.class);

    /** Stripe pagina de cien en cien; se piden los máximos por vuelta. */
    private static final int POR_PAGINA = 100;

    /** Tope de seguridad: no más de 25 páginas por consulta. */
    private static final int PAGINAS_MAXIMAS = 25;

    private final StripeClient stripe;

    public ServicioStripe(PropiedadesVbstats propiedades) {
        this.stripe = propiedades.hayStripe() ? StripeClient.builder()
                .setApiKey(propiedades.stripeClave())
                .build() : null;
    }

    public boolean configurado() {
        return stripe != null;
    }

    /**
     * Lo cobrado en el periodo.
     *
     * @param facturado  neto en euros, ya restadas las devoluciones del periodo.
     * @param devuelto   lo devuelto, en positivo.
     * @param porDia     serie diaria del neto.
     * @param porPlan    reparto por descripción del cobro.
     * @param movimientos los últimos cobros, del más reciente al más antiguo.
     */
    public record Cobros(
            double facturado,
            double devuelto,
            Serie porDia,
            Reparto porPlan,
            List<com.bluedebug.gestion.conectores.modelo.Ingresos.Movimiento> movimientos
    ) {}

    @org.springframework.cache.annotation.Cacheable(
            value = "stripe", key = "#rango.desde() + '|' + #rango.hasta()")
    public Cobros cobros(Rango rango) {
        if (stripe == null) {
            return vacio(rango);
        }

        List<Charge> cargos;
        try {
            cargos = todosLosCargos(rango);
        } catch (Exception e) {
            log.warn("VBStats: no se pudo leer Stripe: {}", e.getMessage());
            return vacio(rango);
        }

        Map<LocalDate, Double> porDia = new HashMap<>();
        Map<String, Double> porPlan = new LinkedHashMap<>();
        List<com.bluedebug.gestion.conectores.modelo.Ingresos.Movimiento> movimientos = new ArrayList<>();
        double facturado = 0;
        double devuelto = 0;

        for (Charge cargo : cargos) {
            if (!Boolean.TRUE.equals(cargo.getPaid()) && !"succeeded".equals(cargo.getStatus())) {
                // Un intento fallido no es dinero. Se guarda para la tabla, con su
                // estado, pero no suma en ninguna cifra.
                movimientos.add(movimiento(cargo, 0));
                continue;
            }

            double importe = cargo.getAmount() / 100d;
            double reembolsado = (cargo.getAmountRefunded() == null ? 0 : cargo.getAmountRefunded()) / 100d;
            double neto = importe - reembolsado;

            facturado += neto;
            devuelto += reembolsado;

            LocalDate dia = rango.diaDe(java.time.Instant.ofEpochSecond(cargo.getCreated()));
            porDia.merge(dia, neto, Double::sum);
            porPlan.merge(plan(cargo), neto, Double::sum);
            movimientos.add(movimiento(cargo, neto));
        }

        return new Cobros(
                redondear(facturado),
                redondear(devuelto),
                rango.rellenar("facturado", "Facturado", "dinero", porDia),
                new Reparto("por_plan", "Facturación por plan",
                        porPlan.entrySet().stream()
                                .map(e -> Reparto.Trozo.de(e.getKey(), redondear(e.getValue())))
                                .toList()),
                movimientos.stream().limit(50).toList());
    }

    private List<Charge> todosLosCargos(Rango rango) throws Exception {
        List<Charge> todos = new ArrayList<>();
        String desdeAqui = null;

        for (int pagina = 0; pagina < PAGINAS_MAXIMAS; pagina++) {
            ChargeListParams.Builder params = ChargeListParams.builder()
                    .setLimit((long) POR_PAGINA)
                    .setCreated(ChargeListParams.Created.builder()
                            .setGte(rango.inicio().getEpochSecond())
                            .setLt(rango.fin().getEpochSecond())
                            .build());
            if (desdeAqui != null) {
                params.setStartingAfter(desdeAqui);
            }

            var lote = stripe.charges().list(params.build());
            todos.addAll(lote.getData());

            if (!Boolean.TRUE.equals(lote.getHasMore()) || lote.getData().isEmpty()) {
                break;
            }
            desdeAqui = lote.getData().get(lote.getData().size() - 1).getId();
        }

        return todos;
    }

    private com.bluedebug.gestion.conectores.modelo.Ingresos.Movimiento movimiento(Charge cargo, double neto) {
        String estado = switch (String.valueOf(cargo.getStatus())) {
            case "succeeded" -> Boolean.TRUE.equals(cargo.getRefunded()) ? "devuelto" : "pagado";
            case "pending" -> "pendiente";
            default -> "fallido";
        };

        return new com.bluedebug.gestion.conectores.modelo.Ingresos.Movimiento(
                cargo.getId(),
                java.time.Instant.ofEpochSecond(cargo.getCreated()),
                correo(cargo),
                redondear(neto),
                estado,
                "stripe");
    }

    private String correo(Charge cargo) {
        if (cargo.getBillingDetails() != null && cargo.getBillingDetails().getEmail() != null) {
            return cargo.getBillingDetails().getEmail();
        }
        return cargo.getReceiptEmail();
    }

    /**
     * A qué plan corresponde un cobro.
     *
     * Stripe pone en {@code description} el nombre del producto para los cobros de
     * suscripción, que es lo que se quiere ver. Cuando no viene —pagos sueltos,
     * cobros creados a mano— se agrupa como «otros» en vez de inventar un plan.
     */
    private String plan(Charge cargo) {
        String descripcion = cargo.getDescription();
        return (descripcion == null || descripcion.isBlank()) ? "Otros" : descripcion;
    }

    private Cobros vacio(Rango rango) {
        return new Cobros(0, 0,
                rango.rellenar("facturado", "Facturado", "dinero", Map.of()),
                new Reparto("por_plan", "Facturación por plan", List.of()),
                List.of());
    }

    private double redondear(double valor) {
        return Math.round(valor * 100d) / 100d;
    }
}
