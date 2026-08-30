package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.RegistroConectores;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import com.bluedebug.gestion.conectores.modelo.Ingresos;
import com.bluedebug.gestion.conectores.modelo.Metrica;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Reparto;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import com.bluedebug.gestion.conectores.modelo.Serie;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * La vista de conjunto: todas las apps sumadas en una sola pantalla.
 *
 * Todo lo que cuesta caro pasa por {@link ResumenesCacheados}; aquí solo se juntan
 * los resultados.
 */
@Service
public class ServicioPanel {

    private static final Logger log = LoggerFactory.getLogger(ServicioPanel.class);

    private final RegistroConectores registro;
    private final ResumenesCacheados cache;
    private final VigilanteEstado vigilante;

    public ServicioPanel(RegistroConectores registro, ResumenesCacheados cache, VigilanteEstado vigilante) {
        this.registro = registro;
        this.cache = cache;
        this.vigilante = vigilante;
    }

    /** Una app en el menú: quién es y si responde. */
    public record AppEnMenu(DescriptorApp app, EstadoConector estado) {}

    /**
     * El menú lateral.
     *
     * El estado sale de la foto que toma {@link VigilanteEstado} en segundo plano,
     * no de una sonda en caliente: si no, cada carga de pantalla abriría una
     * conexión a MySQL y otra a Firestore solo para pintar dos puntos de color.
     * Únicamente en los primeros segundos tras arrancar, cuando aún no hay foto,
     * se mira en vivo — es una petición autenticada y con alguien esperando.
     */
    public List<AppEnMenu> menu() {
        return registro.todos().stream()
                .map(c -> new AppEnMenu(
                        c.descriptor(),
                        vigilante.de(c.id()).orElseGet(() -> estadoSeguro(c))))
                .toList();
    }

    public ResumenApp resumen(String id, Rango rango) {
        return cache.resumen(id, rango);
    }

    public Optional<Ingresos> ingresos(String id, Rango rango) {
        return cache.ingresos(id, rango);
    }

    /**
     * El panel general.
     *
     * @param apps    el resumen de cada una, para las tarjetas.
     * @param totales las cifras que sí tienen sentido sumadas entre apps.
     * @param altas   una línea por app con sus altas diarias, para la gráfica comparada.
     * @param porApp  reparto de cuentas por aplicación.
     * @param dinero  lo facturado por las que cobran, sumado.
     */
    public record PanelGeneral(
            List<ResumenApp> apps,
            List<Metrica> totales,
            List<Serie> altas,
            Reparto porApp,
            Ingresos dinero
    ) {}

    public PanelGeneral general(Rango rango) {
        List<ResumenApp> resumenes = new ArrayList<>();
        List<Serie> altas = new ArrayList<>();
        List<Reparto.Trozo> cuentasPorApp = new ArrayList<>();

        double totalCuentas = 0;
        double totalActivos = 0;
        double totalAltas = 0;
        int appsCaidas = 0;

        for (ConectorApp conector : registro.todos()) {
            ResumenApp resumen = cache.resumen(conector.id(), rango);
            resumenes.add(resumen);

            if (!resumen.estado().disponible()) {
                appsCaidas++;
                continue;
            }

            // Se suman por CLAVE, no por posición: cada app declara sus métricas en el
            // orden que quiere, y sumar «la primera de cada una» daría un número sin
            // significado en cuanto una cambie el orden. Las claves de cuentas son dos
            // porque una app de club no tiene «usuarios», tiene fichas.
            double cuentas = valor(resumen, "usuarios") + valor(resumen, "socios");
            totalCuentas += cuentas;
            totalActivos += valor(resumen, "activos");
            totalAltas += valor(resumen, "altas");

            cuentasPorApp.add(new Reparto.Trozo(
                    resumen.app().nombre(), cuentas, resumen.app().color()));

            serie(resumen, "altas").ifPresent(s -> altas.add(new Serie(
                    conector.id(), resumen.app().nombre(), "entero", s.puntos())));
        }

        Ingresos dinero = dineroJunto(rango);

        List<Metrica> totales = new ArrayList<>();
        totales.add(Metrica.entero("cuentas", "Cuentas en total", totalCuentas,
                registro.todos().size() + " aplicaciones"));
        totales.add(Metrica.entero("activos", "Personas activas", totalActivos,
                "han entrado en 30 días"));
        totales.add(Metrica.entero("altas", "Altas en el periodo", totalAltas,
                "últimos " + rango.dias() + " días"));
        totales.add(Metrica.dinero("facturado", "Facturado", dinero.facturado(),
                "Stripe y App Store, antes de comisiones"));
        if (appsCaidas > 0) {
            totales.add(Metrica.entero("caidas", "Apps sin datos", appsCaidas,
                    "revisa sus credenciales"));
        }

        return new PanelGeneral(
                resumenes,
                totales,
                altas,
                new Reparto("por_app", "Cuentas por aplicación", cuentasPorApp),
                dinero);
    }

    /** La facturación de todas las apps que cobren, en una sola serie. */
    private Ingresos dineroJunto(Rango rango) {
        Map<LocalDate, Double> porDia = new HashMap<>();
        Map<String, Double> porApp = new LinkedHashMap<>();
        List<Ingresos.Movimiento> movimientos = new ArrayList<>();
        double facturado = 0;
        double devuelto = 0;
        double recurrente = 0;
        int suscriptores = 0;

        for (ConectorApp conector : registro.todos()) {
            Ingresos ingresos = cache.ingresos(conector.id(), rango).orElse(null);
            if (ingresos == null) {
                continue;
            }

            facturado += ingresos.facturado();
            devuelto += ingresos.devuelto();
            recurrente += ingresos.recurrente();
            suscriptores += ingresos.suscriptores();
            porApp.merge(conector.descriptor().nombre(), ingresos.facturado(), Double::sum);
            movimientos.addAll(ingresos.movimientos());

            for (Serie.Punto punto : ingresos.porDia().puntos()) {
                porDia.merge(punto.fecha(), punto.valor(), Double::sum);
            }
        }

        movimientos.sort((a, b) -> b.fecha().compareTo(a.fecha()));

        return new Ingresos(
                "eur", facturado, devuelto, recurrente, suscriptores,
                rango.rellenar("facturado", "Facturado", "dinero", porDia),
                new Reparto("por_app", "Facturación por aplicación",
                        porApp.entrySet().stream()
                                .map(e -> Reparto.Trozo.de(e.getKey(), e.getValue()))
                                .toList()),
                movimientos.stream().limit(50).toList());
    }

    /**
     * Todas las cuentas de todas las apps, con el nombre de su app pegado.
     *
     * Sirve para la pantalla de usuarios global, donde tiene sentido buscar un
     * correo sin saber de antemano en qué app está.
     */
    public record UsuarioGlobal(String appId, String appNombre, String appColor, UsuarioApp usuario) {}

    public List<UsuarioGlobal> todosLosUsuarios() {
        List<UsuarioGlobal> gente = new ArrayList<>();

        for (ConectorApp conector : registro.todos()) {
            if (!conector.descriptor().puede(DescriptorApp.Capacidad.USUARIOS)) {
                continue;
            }
            try {
                for (UsuarioApp usuario : conector.usuarios()) {
                    gente.add(new UsuarioGlobal(
                            conector.id(),
                            conector.descriptor().nombre(),
                            conector.descriptor().color(),
                            usuario));
                }
            } catch (Exception e) {
                log.error("Falló la lista de usuarios de {}", conector.id(), e);
            }
        }

        gente.sort(porUltimaSesion());
        return gente;
    }

    /** El más reciente arriba, y quien nunca ha entrado, al final. */
    private java.util.Comparator<UsuarioGlobal> porUltimaSesion() {
        return (a, b) -> {
            var ua = a.usuario().ultimaSesion();
            var ub = b.usuario().ultimaSesion();
            if (ua == null && ub == null) return 0;
            if (ua == null) return 1;
            if (ub == null) return -1;
            return ub.compareTo(ua);
        };
    }

    private EstadoConector estadoSeguro(ConectorApp conector) {
        try {
            return conector.estado();
        } catch (Exception e) {
            log.error("Falló la comprobación de estado de {}", conector.id(), e);
            return EstadoConector.sinConfigurar("No se pudo comprobar. La traza está en el log.");
        }
    }

    private double valor(ResumenApp resumen, String clave) {
        return resumen.metricas().stream()
                .filter(m -> m.clave().equals(clave))
                .mapToDouble(Metrica::valor)
                .findFirst()
                .orElse(0d);
    }

    private Optional<Serie> serie(ResumenApp resumen, String clave) {
        return resumen.series().stream().filter(s -> s.clave().equals(clave)).findFirst();
    }
}
