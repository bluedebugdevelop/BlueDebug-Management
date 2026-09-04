package com.bluedebug.gestion.contabilidad;

import com.bluedebug.gestion.comun.NoEncontrado;
import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.RegistroConectores;
import com.bluedebug.gestion.conectores.modelo.Metrica;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Reparto;
import com.bluedebug.gestion.conectores.modelo.Serie;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Los gastos de BlueDebug: alta, corrección y las cuentas que salen de ellos.
 *
 * Esto NO es un conector y no tiene que serlo. Un conector administra una app de
 * fuera; aquí no hay ninguna app detrás, son los gastos de la propia empresa, y
 * el dato lo pone quien escribe en el formulario. Por eso vive al lado de
 * {@code panel} y no dentro de {@code conectores}.
 *
 * Lo único que toma prestado de los conectores es el lenguaje con el que el front
 * ya sabe pintar —{@link Metrica}, {@link Serie}, {@link Reparto}—, y así las
 * gráficas de esta pantalla son las mismas piezas del panel general en vez de
 * unas nuevas.
 *
 * Del dinero se hace TODA la aritmética con BigDecimal y solo se pasa a double al
 * armar la métrica que se manda al front. El motivo largo está en {@link Gasto}.
 */
@Service
public class ServicioContabilidad {

    /** Nada anterior a esto es un gasto de BlueDebug: la empresa se fundó en 2025. */
    private static final int PRIMER_ANO = 2025;

    private final RepositorioGastos repositorio;
    private final FuenteContabilidad fuente;
    private final PropiedadesContabilidad propiedades;
    private final RegistroConectores conectores;

    public ServicioContabilidad(RepositorioGastos repositorio,
                                FuenteContabilidad fuente,
                                PropiedadesContabilidad propiedades,
                                RegistroConectores conectores) {
        this.repositorio = repositorio;
        this.fuente = fuente;
        this.propiedades = propiedades;
        this.conectores = conectores;
    }

    // ------------------------------------------------------------------ arranque

    /** Una app a la que se puede imputar un gasto. */
    public record AppImputable(String id, String nombre, String color) {}

    /**
     * Todo lo que el formulario necesita para pintarse: los socios, las
     * categorías, las recurrencias, las apps y los años que tienen apuntes.
     *
     * Va en una sola llamada por lo mismo que {@code /api/panel/arranque}: son
     * cinco listas que no cambian mientras la pantalla está abierta.
     */
    public record Catalogo(
            boolean configurado,
            String motivo,
            List<String> socios,
            List<CategoriaGasto.Opcion> categorias,
            List<Recurrencia.Opcion> recurrencias,
            List<AppImputable> apps,
            List<Integer> anios
    ) {}

    public Catalogo catalogo() {
        List<AppImputable> apps = conectores.todos().stream()
                .map(ConectorApp::descriptor)
                .map(d -> new AppImputable(d.id(), d.nombre(), d.color()))
                .toList();

        if (!fuente.disponible()) {
            return new Catalogo(false, fuente.motivo(), propiedades.sociosLimpios(),
                    CategoriaGasto.opciones(), Recurrencia.opciones(), apps, List.of(anioActual()));
        }

        return new Catalogo(true, null, propiedades.sociosLimpios(),
                CategoriaGasto.opciones(), Recurrencia.opciones(), apps, anios());
    }

    /**
     * Los años que ofrece el selector: desde el primero con apuntes hasta el
     * actual, sin huecos. Un año sin gastos en medio también se puede abrir —sale
     * vacío, que es la respuesta correcta— porque un desplegable al que le faltan
     * años parece roto.
     */
    private List<Integer> anios() {
        int actual = anioActual();
        int primero = repositorio.anosConDatos().map(rango -> rango[0]).orElse(actual);
        primero = Math.min(primero, actual);

        List<Integer> anios = new ArrayList<>();
        for (int a = actual; a >= primero; a--) {
            anios.add(a);
        }
        return anios;
    }

    private int anioActual() {
        return LocalDate.now(Rango.ZONA_CASA).getYear();
    }

    /**
     * El instante en que se apunta algo, al segundo.
     *
     * Se trunca a propósito, y no es cosmético: {@code Instant.now()} da
     * nanosegundos, y lo que la columna guarda depende del motor —Postgres
     * conserva microsegundos, y el TIMESTAMP de MySQL, si no se le pide
     * precisión, se queda en el segundo entero—. Sin truncar, el objeto que
     * devuelve un alta y el que se lee después de la base NO son iguales, y lo
     * son de una forma distinta en cada motor.
     *
     * Al segundo no se pierde nada: este campo contesta «quién apuntó esto y
     * cuándo», no cronometra.
     */
    private Instant ahora() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    // --------------------------------------------------------------------- alta

    public Gasto crear(AltaGasto alta, String quien) {
        exigirBaseDeDatos();

        Gasto gasto = new Gasto(
                UUID.randomUUID().toString(),
                fecha(alta.fecha()),
                texto(alta.concepto(), "concepto", 200, true),
                categoria(alta.categoria()),
                texto(alta.proveedor(), "proveedor", 120, false),
                importe(alta.importe()),
                iva(alta.iva(), alta.importe()),
                socio(alta.pagadoPor()),
                app(alta.app()),
                recurrencia(alta.recurrencia()),
                texto(alta.nota(), "nota", 500, false),
                quien,
                ahora());

        repositorio.insertar(gasto);
        return gasto;
    }

    public Gasto editar(String id, AltaGasto alta) {
        exigirBaseDeDatos();

        Gasto previo = repositorio.buscar(id)
                .orElseThrow(() -> new NoEncontrado("Ese gasto ya no está. ¿Lo ha borrado alguien?"));

        Gasto gasto = new Gasto(
                previo.id(),
                fecha(alta.fecha()),
                texto(alta.concepto(), "concepto", 200, true),
                categoria(alta.categoria()),
                texto(alta.proveedor(), "proveedor", 120, false),
                importe(alta.importe()),
                iva(alta.iva(), alta.importe()),
                socio(alta.pagadoPor()),
                app(alta.app()),
                recurrencia(alta.recurrencia()),
                texto(alta.nota(), "nota", 500, false),
                // Quién y cuándo lo apuntó no se tocan: ver RepositorioGastos.actualizar.
                previo.creadoPor(),
                previo.creadoEn());

        repositorio.actualizar(gasto);
        return gasto;
    }

    public Gasto borrar(String id) {
        exigirBaseDeDatos();

        Gasto gasto = repositorio.buscar(id)
                .orElseThrow(() -> new NoEncontrado("Ese gasto ya no está."));

        repositorio.borrar(id);
        return gasto;
    }

    // ------------------------------------------------------------------ resumen

    /**
     * La pantalla entera de un año.
     *
     * @param anio         el año mirado, o 0 si se están viendo todos.
     * @param gastos       los apuntes, del más reciente al más antiguo.
     * @param metricas     las tarjetas de arriba.
     * @param porMes       lo gastado cada mes.
     * @param porCategoria en qué se ha ido.
     * @param porSocio     quién ha puesto el dinero.
     * @param porApp       a qué app se le imputa.
     * @param liquidacion  quién debe a quién.
     */
    public record ResumenContabilidad(
            int anio,
            List<Gasto> gastos,
            List<Metrica> metricas,
            Serie porMes,
            Reparto porCategoria,
            Reparto porSocio,
            Reparto porApp,
            Liquidacion liquidacion
    ) {}

    public ResumenContabilidad resumen(int anio) {
        exigirBaseDeDatos();

        List<Gasto> gastos = repositorio.delAnio(anio);
        List<String> socios = propiedades.sociosLimpios();

        BigDecimal total = suma(gastos, Gasto::importe);
        BigDecimal ivaTotal = suma(gastos, Gasto::iva);

        return new ResumenContabilidad(
                anio,
                gastos,
                metricas(gastos, total, ivaTotal, anio),
                porMes(gastos, anio),
                porCategoria(gastos),
                porSocio(gastos, socios),
                porApp(gastos),
                Liquidacion.de(socios, pagadoPorCadaUno(gastos, socios)));
    }

    private List<Metrica> metricas(List<Gasto> gastos, BigDecimal total, BigDecimal iva, int anio) {
        List<Metrica> metricas = new ArrayList<>();

        String periodo = anio > 0 ? "en " + anio : "desde el principio";
        metricas.add(Metrica.dinero("total", "Gastado " + periodo, total.doubleValue(),
                gastos.size() + (gastos.size() == 1 ? " apunte" : " apuntes")));

        metricas.add(Metrica.dinero("fijo", "Coste fijo al mes", costeFijoMensual(gastos).doubleValue(),
                "suscripciones y licencias, prorrateadas"));

        metricas.add(Metrica.dinero("media", "Media mensual", mediaMensual(gastos, total, anio).doubleValue(),
                "sobre los meses transcurridos"));

        metricas.add(Metrica.dinero("iva", "IVA soportado", iva.doubleValue(),
                "incluido en el total de arriba"));

        return metricas;
    }

    /**
     * Lo que cuesta tener esto en pie un mes cualquiera.
     *
     * Se cuentan solo los gastos marcados como recurrentes y se prorratean: los
     * 99 € al año de Apple son 8,25 € al mes. Los pagos únicos —un portátil, una
     * gestoría de una vez— quedan fuera a posta: la pregunta que responde esta
     * cifra es «si dejamos de vender mañana, cuánto sigue saliendo», y un portátil
     * ya comprado no sigue saliendo.
     *
     * Se mira SOLO EL ÚLTIMO PAGO de cada concepto recurrente. Si no, un año con
     * doce recibos de Railway apuntados contaría doce veces el mismo coste fijo.
     * El concepto es la clave, así que dos recibos del mismo servicio tienen que
     * escribirse igual — y por eso el formulario recuerda los conceptos ya usados.
     */
    BigDecimal costeFijoMensual(List<Gasto> gastos) {
        Map<String, Gasto> ultimoDeCada = new LinkedHashMap<>();

        for (Gasto gasto : gastos) {
            if (gasto.recurrencia() == Recurrencia.UNICO) {
                continue;
            }
            String clave = gasto.concepto().trim().toLowerCase();
            Gasto previo = ultimoDeCada.get(clave);
            if (previo == null || gasto.fecha().isAfter(previo.fecha())) {
                ultimoDeCada.put(clave, gasto);
            }
        }

        return ultimoDeCada.values().stream()
                .map(g -> g.recurrencia().alMes(g.importe()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * El gasto medio por mes.
     *
     * El divisor son los meses TRANSCURRIDOS, no doce: en septiembre, dividir lo
     * gastado en el año entre doce da una media que parece la mitad de lo que
     * realmente se está gastando.
     */
    private BigDecimal mediaMensual(List<Gasto> gastos, BigDecimal total, int anio) {
        int meses = mesesTranscurridos(gastos, anio);
        if (meses <= 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(BigDecimal.valueOf(meses), 2, RoundingMode.HALF_UP);
    }

    private int mesesTranscurridos(List<Gasto> gastos, int anio) {
        YearMonth ahora = YearMonth.now(Rango.ZONA_CASA);

        if (anio > 0) {
            if (anio > ahora.getYear()) {
                return 0;
            }
            return anio == ahora.getYear() ? ahora.getMonthValue() : 12;
        }

        // Sin año concreto, desde el primer apunte hasta hoy.
        return gastos.stream()
                .map(g -> YearMonth.from(g.fecha()))
                .min(YearMonth::compareTo)
                .map(primero -> (int) (java.time.temporal.ChronoUnit.MONTHS.between(primero, ahora) + 1))
                .orElse(0);
    }

    /**
     * Lo gastado mes a mes.
     *
     * Se rellenan todos los meses del año, también los que van a cero, por el
     * mismo motivo que se rellenan los días en las series del panel: una gráfica
     * que se salta los meses vacíos pinta una línea plana donde hubo un parón.
     * La fecha de cada punto es el día 1, que es lo que el front sabe leer.
     */
    private Serie porMes(List<Gasto> gastos, int anio) {
        Map<YearMonth, BigDecimal> porMes = new LinkedHashMap<>();
        for (Gasto gasto : gastos) {
            porMes.merge(YearMonth.from(gasto.fecha()), gasto.importe(), BigDecimal::add);
        }

        YearMonth primero;
        YearMonth ultimo;
        if (anio > 0) {
            primero = YearMonth.of(anio, 1);
            ultimo = YearMonth.of(anio, 12);
        } else if (porMes.isEmpty()) {
            primero = YearMonth.now(Rango.ZONA_CASA);
            ultimo = primero;
        } else {
            primero = porMes.keySet().stream().min(YearMonth::compareTo).orElseThrow();
            ultimo = porMes.keySet().stream().max(YearMonth::compareTo).orElseThrow();
        }

        List<Serie.Punto> puntos = new ArrayList<>();
        for (YearMonth mes = primero; !mes.isAfter(ultimo); mes = mes.plusMonths(1)) {
            BigDecimal valor = porMes.getOrDefault(mes, BigDecimal.ZERO);
            puntos.add(new Serie.Punto(mes.atDay(1), valor.doubleValue()));
        }

        return new Serie("gasto_mes", "Gasto mensual", "dinero", puntos);
    }

    private Reparto porCategoria(List<Gasto> gastos) {
        Map<CategoriaGasto, BigDecimal> acumulado = new LinkedHashMap<>();
        for (Gasto gasto : gastos) {
            acumulado.merge(gasto.categoria(), gasto.importe(), BigDecimal::add);
        }

        List<Reparto.Trozo> trozos = acumulado.entrySet().stream()
                .sorted(Map.Entry.<CategoriaGasto, BigDecimal>comparingByValue().reversed())
                .map(e -> new Reparto.Trozo(e.getKey().etiqueta(), e.getValue().doubleValue(), e.getKey().color()))
                .toList();

        return new Reparto("por_categoria", "En qué se va", trozos);
    }

    /**
     * Cuánto ha puesto cada socio.
     *
     * Salen TODOS los socios, incluido el que no haya pagado nada: un socio que
     * desaparece de la gráfica porque este mes no le tocó pagar nada se lee como
     * un error, y además ver el cero es justo la información útil.
     */
    private Reparto porSocio(List<Gasto> gastos, List<String> socios) {
        Map<String, BigDecimal> pagado = pagadoPorCadaUno(gastos, socios);

        List<Reparto.Trozo> trozos = pagado.entrySet().stream()
                .map(e -> Reparto.Trozo.de(e.getKey(), e.getValue().doubleValue()))
                .toList();

        return new Reparto("por_socio", "Quién lo ha pagado", trozos);
    }

    /**
     * @return lo puesto por cada socio, con los socios de la configuración primero
     *         y, detrás, cualquier nombre que esté en la tabla y ya no esté en la
     *         lista. Ese caso —alguien que se va, o un nombre que se reescribe en
     *         la configuración— no puede hacer que su dinero desaparezca del total.
     */
    private Map<String, BigDecimal> pagadoPorCadaUno(List<Gasto> gastos, List<String> socios) {
        Map<String, BigDecimal> pagado = new LinkedHashMap<>();
        socios.forEach(socio -> pagado.put(socio, BigDecimal.ZERO));

        for (Gasto gasto : gastos) {
            pagado.merge(gasto.pagadoPor(), gasto.importe(), BigDecimal::add);
        }
        return pagado;
    }

    private Reparto porApp(List<Gasto> gastos) {
        Map<String, BigDecimal> acumulado = new LinkedHashMap<>();
        Map<String, String> colores = new LinkedHashMap<>();

        for (Gasto gasto : gastos) {
            String nombre = "Empresa";
            String color = null;
            if (gasto.app() != null) {
                var descriptor = conectores.todos().stream()
                        .map(ConectorApp::descriptor)
                        .filter(d -> d.id().equals(gasto.app()))
                        .findFirst();
                // Un gasto imputado a una app cuyo conector ya no existe sigue contando;
                // se enseña con su id para que se vea que hay que recolocarlo.
                nombre = descriptor.map(d -> d.nombre()).orElse(gasto.app());
                color = descriptor.map(d -> d.color()).orElse(null);
            }
            acumulado.merge(nombre, gasto.importe(), BigDecimal::add);
            colores.putIfAbsent(nombre, color);
        }

        List<Reparto.Trozo> trozos = acumulado.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new Reparto.Trozo(e.getKey(), e.getValue().doubleValue(), colores.get(e.getKey())))
                .toList();

        return new Reparto("por_app", "A qué se imputa", trozos);
    }

    private BigDecimal suma(List<Gasto> gastos, java.util.function.Function<Gasto, BigDecimal> campo) {
        return gastos.stream().map(campo).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- validación

    /*
      Todo lo que llega del formulario se valida aquí y no con anotaciones de
      Bean Validation. Es a propósito: los mensajes salen escritos para quien está
      delante de la pantalla ('el importe tiene que ser mayor que cero') en vez
      del 'must be greater than 0' que produce el validador, y esta pantalla la
      usan tres personas que no van a ir a mirar un log.
    */

    private void exigirBaseDeDatos() {
        if (!fuente.configurado()) {
            throw new PeticionInvalida(fuente.motivo());
        }
    }

    private LocalDate fecha(LocalDate fecha) {
        if (fecha == null) {
            throw new PeticionInvalida("Falta la fecha del pago");
        }
        if (fecha.getYear() < PRIMER_ANO) {
            throw new PeticionInvalida("La fecha es anterior a " + PRIMER_ANO + ", que es cuando nació BlueDebug");
        }
        // Se admite un gasto con fecha futura —una factura ya emitida que se paga el
        // mes que viene— pero no un año que viene: eso siempre es un dedo torcido.
        if (fecha.isAfter(LocalDate.now(Rango.ZONA_CASA).plusMonths(12))) {
            throw new PeticionInvalida("Esa fecha está a más de un año vista. ¿Seguro?");
        }
        return fecha;
    }

    private String texto(String valor, String campo, int maximo, boolean obligatorio) {
        String limpio = valor == null ? "" : valor.trim();
        if (limpio.isEmpty()) {
            if (obligatorio) {
                throw new PeticionInvalida("Falta el " + campo);
            }
            return null;
        }
        if (limpio.length() > maximo) {
            throw new PeticionInvalida("El " + campo + " no puede pasar de " + maximo + " caracteres");
        }
        return limpio;
    }

    private BigDecimal importe(BigDecimal valor) {
        if (valor == null) {
            throw new PeticionInvalida("Falta el importe");
        }
        BigDecimal centimos = valor.setScale(2, RoundingMode.HALF_UP);
        if (centimos.signum() <= 0) {
            throw new PeticionInvalida("El importe tiene que ser mayor que cero");
        }
        // La columna es DECIMAL(12,2): un número más gordo que esto no entra, y es
        // mejor decirlo que dejar que reviente el driver con su propio mensaje.
        if (centimos.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new PeticionInvalida("Ese importe es demasiado grande");
        }
        return centimos;
    }

    private BigDecimal iva(BigDecimal valor, BigDecimal importe) {
        if (valor == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal centimos = valor.setScale(2, RoundingMode.HALF_UP);
        if (centimos.signum() < 0) {
            throw new PeticionInvalida("El IVA no puede ser negativo");
        }
        // El IVA va DENTRO del importe, no aparte. Si se cuela uno mayor que el
        // total, la base saldría negativa y el error se arrastraría a las sumas.
        if (importe != null && centimos.compareTo(importe.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new PeticionInvalida("El IVA no puede ser mayor que el importe: va incluido en él");
        }
        return centimos;
    }

    private CategoriaGasto categoria(String valor) {
        return CategoriaGasto.de(valor)
                .orElseThrow(() -> new PeticionInvalida("Esa categoría no existe"));
    }

    private Recurrencia recurrencia(String valor) {
        if (valor == null || valor.isBlank()) {
            return Recurrencia.UNICO;
        }
        return Recurrencia.de(valor)
                .orElseThrow(() -> new PeticionInvalida("Esa recurrencia no existe"));
    }

    /**
     * El pagador tiene que ser uno de los socios configurados.
     *
     * Sin esta comprobación, un typo en el nombre crea un cuarto socio fantasma
     * con su propio saldo y la liquidación deja de cuadrar sin que se vea por qué.
     */
    private String socio(String valor) {
        String limpio = valor == null ? "" : valor.trim();
        List<String> socios = propiedades.sociosLimpios();

        return socios.stream()
                .filter(s -> s.equalsIgnoreCase(limpio))
                .findFirst()
                .orElseThrow(() -> new PeticionInvalida(
                        "Hay que decir quién lo pagó, y tiene que ser uno de: " + String.join(", ", socios)));
    }

    /**
     * La imputación a una app es opcional, pero si va, tiene que existir.
     *
     * Esta es la única mención a los conectores en todo el paquete y es genérica:
     * se pregunta al registro si ese id existe, sin saber cuál es. La app número
     * siete aparecerá en el desplegable el día que se escriba su clase.
     */
    private String app(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String limpio = valor.trim();
        boolean existe = conectores.todos().stream().anyMatch(c -> c.id().equals(limpio));
        if (!existe) {
            throw new PeticionInvalida("No hay ninguna aplicación con el id '" + limpio + "'");
        }
        return limpio;
    }
}
