package com.bluedebug.gestion.contabilidad;

import com.bluedebug.gestion.comun.NoEncontrado;
import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.RegistroConectores;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import com.bluedebug.gestion.conectores.modelo.Metrica;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.Reparto;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La contabilidad entera contra una base de datos de verdad.
 *
 * Con mocks esta prueba no valdría para nada: lo que se quiere comprobar es
 * justo lo que pasa al cruzar el JDBC —que el DDL se acepta, que un
 * DECIMAL(12,2) vuelve con los mismos céntimos que se guardaron, que una fecha
 * no se desplaza un día— y eso no lo puede fingir un mock. Se usa H2 porque
 * traga el mismo SQL sin sintaxis propia que Postgres y MySQL, que son los dos
 * motores que se van a usar de verdad.
 *
 * Cada prueba estrena base: {@code DB_CLOSE_DELAY=-1} con un nombre distinto por
 * ejecución evita el clásico test que pasa solo si va el primero.
 */
class ContabilidadTest {

    private static final List<String> SOCIOS = List.of("Adrián Estrada", "Diego Charro", "Rubén Rubio");

    private ServicioContabilidad servicio;
    private RepositorioGastos repositorio;

    @BeforeEach
    void montar() {
        String url = "jdbc:h2:mem:contabilidad-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var propiedades = new PropiedadesContabilidad(url, "sa", "", SOCIOS);
        var fuente = new FuenteContabilidad(propiedades);

        repositorio = new RepositorioGastos(fuente);
        servicio = new ServicioContabilidad(repositorio, fuente, propiedades, registroDePrueba());
    }

    @Test
    @DisplayName("el DDL se acepta y un gasto vuelve tal cual se guardó")
    void idaYVuelta() {
        Gasto guardado = servicio.crear(alta("Railway", "20.57", "2026-08-01"), "adrian@bluedebug.com");

        Gasto leido = repositorio.buscar(guardado.id()).orElseThrow();

        assertEquals("Railway", leido.concepto());
        // Los céntimos son los que más fácil se pierden al cruzar el driver.
        assertEquals(new BigDecimal("20.57"), leido.importe());
        assertEquals(LocalDate.of(2026, 8, 1), leido.fecha());
        assertEquals("Adrián Estrada", leido.pagadoPor());
        assertEquals(CategoriaGasto.HOSTING, leido.categoria());
        assertEquals("adrian@bluedebug.com", leido.creadoPor());
    }

    @Test
    @DisplayName("el resumen suma lo del año y deja fuera lo de otros años")
    void resumenPorAno() {
        servicio.crear(alta("Railway", "20.00", "2026-03-01"), "yo@bluedebug.com");
        servicio.crear(alta("Railway", "20.00", "2026-04-01"), "yo@bluedebug.com");
        servicio.crear(alta("Dominio", "12.00", "2025-11-20"), "yo@bluedebug.com");

        var resumen = servicio.resumen(2026);

        assertEquals(2, resumen.gastos().size());
        assertEquals(40d, metrica(resumen.metricas(), "total").valor());
        // Doce meses en la gráfica aunque solo dos tengan gasto: ver ServicioContabilidad.
        assertEquals(12, resumen.porMes().puntos().size());
        assertEquals(40d, resumen.porMes().total());
    }

    @Test
    @DisplayName("el reparto entre socios sale de lo que ha puesto cada uno")
    void repartoEntreSocios() {
        servicio.crear(alta("Licencia Apple", "99.00", "2026-01-15", "Adrián Estrada"), "yo@bluedebug.com");
        servicio.crear(alta("Play Console", "24.00", "2026-01-16", "Diego Charro"), "yo@bluedebug.com");

        var liquidacion = servicio.resumen(2026).liquidacion();

        assertEquals(new BigDecimal("123.00"), liquidacion.total());
        assertEquals(new BigDecimal("41.00"), liquidacion.porCabeza());
        // Rubén no ha pagado nada y debe su parte entera.
        assertTrue(liquidacion.ajustes().stream().anyMatch(a -> a.de().equals("Rubén Rubio")));
    }

    @Test
    @DisplayName("un gasto sin app se imputa a la empresa y con app, a la app")
    void imputacion() {
        servicio.crear(alta("Gestoría", "80.00", "2026-02-01"), "yo@bluedebug.com");
        servicio.crear(conApp(alta("Railway de VBStats", "20.00", "2026-02-02"), "vbstats"), "yo@bluedebug.com");

        Reparto porApp = servicio.resumen(2026).porApp();

        assertEquals(80d, trozo(porApp, "Empresa"));
        assertEquals(20d, trozo(porApp, "VBStats"));
    }

    @Test
    @DisplayName("editar cambia el gasto pero no quién lo apuntó")
    void edicionConservaElRastro() {
        Gasto original = servicio.crear(alta("Railwai", "20.00", "2026-05-01"), "adrian@bluedebug.com");

        AltaGasto corregido = new AltaGasto(
                LocalDate.of(2026, 5, 1), "Railway", "HOSTING", null,
                new BigDecimal("22.00"), BigDecimal.ZERO, "Diego Charro", null, "MENSUAL", null);

        Gasto tras = servicio.editar(original.id(), corregido);

        assertEquals("Railway", tras.concepto());
        assertEquals(new BigDecimal("22.00"), tras.importe());
        assertEquals("Diego Charro", tras.pagadoPor());
        assertEquals("adrian@bluedebug.com", tras.creadoPor());
        // Comparación exacta: el servicio trunca a segundos al crear justo para
        // que lo que se guarda y lo que se lee sean el mismo instante en
        // cualquier motor. Si esto vuelve a fallar por los últimos dígitos, es
        // que alguien ha quitado ese truncado.
        assertEquals(original.creadoEn(), tras.creadoEn());
    }

    @Test
    @DisplayName("borrar quita el gasto de los totales")
    void borrado() {
        Gasto gasto = servicio.crear(alta("Un error", "500.00", "2026-06-01"), "yo@bluedebug.com");

        servicio.borrar(gasto.id());

        assertTrue(repositorio.buscar(gasto.id()).isEmpty());
        assertEquals(0d, metrica(servicio.resumen(2026).metricas(), "total").valor());
    }

    @Test
    @DisplayName("borrar algo que ya no está da un 404, no un 500")
    void borrarLoQueNoExiste() {
        assertThrows(NoEncontrado.class, () -> servicio.borrar("no-existe"));
    }

    @Test
    @DisplayName("el pagador tiene que ser uno de los socios")
    void pagadorDesconocido() {
        AltaGasto conIntruso = alta("Algo", "10.00", "2026-01-01", "Fulano");

        PeticionInvalida fallo = assertThrows(PeticionInvalida.class,
                () -> servicio.crear(conIntruso, "yo@bluedebug.com"));

        assertTrue(fallo.getMessage().contains("Adrián Estrada"), fallo.getMessage());
    }

    @Test
    @DisplayName("no se admite un importe de cero ni negativo")
    void importeInvalido() {
        AltaGasto cero = new AltaGasto(LocalDate.of(2026, 1, 1), "Algo", "OTROS", null,
                BigDecimal.ZERO, BigDecimal.ZERO, "Diego Charro", null, "UNICO", null);

        assertThrows(PeticionInvalida.class, () -> servicio.crear(cero, "yo@bluedebug.com"));
    }

    @Test
    @DisplayName("el IVA no puede pasarse del importe: va incluido en él")
    void ivaMayorQueElImporte() {
        AltaGasto raro = new AltaGasto(LocalDate.of(2026, 1, 1), "Algo", "OTROS", null,
                new BigDecimal("100.00"), new BigDecimal("150.00"), "Diego Charro", null, "UNICO", null);

        assertThrows(PeticionInvalida.class, () -> servicio.crear(raro, "yo@bluedebug.com"));
    }

    @Test
    @DisplayName("imputar a una app que no existe se rechaza con su motivo")
    void appInventada() {
        AltaGasto aNadaConocido = conApp(alta("Algo", "10.00", "2026-01-01"), "app-fantasma");

        PeticionInvalida fallo = assertThrows(PeticionInvalida.class,
                () -> servicio.crear(aNadaConocido, "yo@bluedebug.com"));

        assertTrue(fallo.getMessage().contains("app-fantasma"), fallo.getMessage());
    }

    @Test
    @DisplayName("sin ningún apunte el resumen sale vacío, no roto")
    void sinNada() {
        var resumen = servicio.resumen(2026);

        assertTrue(resumen.gastos().isEmpty());
        assertEquals(0d, metrica(resumen.metricas(), "total").valor());
        assertTrue(resumen.liquidacion().ajustes().isEmpty());
        assertEquals(12, resumen.porMes().puntos().size());
    }

    @Test
    @DisplayName("el catálogo dice que está configurado y con qué socios")
    void catalogo() {
        var catalogo = servicio.catalogo();

        assertTrue(catalogo.configurado());
        assertNull(catalogo.motivo());
        assertEquals(SOCIOS, catalogo.socios());
        assertTrue(catalogo.categorias().size() > 5);
        assertNotNull(catalogo.apps());
    }

    @Test
    @DisplayName("sin base de datos configurada, el catálogo lo dice en vez de reventar")
    void sinBaseDeDatos() {
        var propiedades = new PropiedadesContabilidad("", null, null, SOCIOS);
        var apagado = new ServicioContabilidad(
                new RepositorioGastos(new FuenteContabilidad(propiedades)),
                new FuenteContabilidad(propiedades),
                propiedades,
                registroDePrueba());

        var catalogo = apagado.catalogo();

        assertTrue(!catalogo.configurado());
        assertTrue(catalogo.motivo().contains("BLUEDEBUG_CONTABILIDAD_URL"));
        // Los socios y las categorías siguen viniendo: la pantalla los usa para
        // explicar qué habrá cuando se configure.
        assertEquals(SOCIOS, catalogo.socios());
    }

    // ------------------------------------------------------------------- ayudas

    private AltaGasto alta(String concepto, String importe, String fecha) {
        return alta(concepto, importe, fecha, "Adrián Estrada");
    }

    private AltaGasto alta(String concepto, String importe, String fecha, String socio) {
        return new AltaGasto(
                LocalDate.parse(fecha), concepto, "HOSTING", null,
                new BigDecimal(importe), BigDecimal.ZERO, socio, null, "UNICO", null);
    }

    private AltaGasto conApp(AltaGasto base, String app) {
        return new AltaGasto(base.fecha(), base.concepto(), base.categoria(), base.proveedor(),
                base.importe(), base.iva(), base.pagadoPor(), app, base.recurrencia(), base.nota());
    }

    private Metrica metrica(List<Metrica> metricas, String clave) {
        return metricas.stream().filter(m -> m.clave().equals(clave)).findFirst().orElseThrow();
    }

    private double trozo(Reparto reparto, String etiqueta) {
        return reparto.trozos().stream()
                .filter(t -> t.etiqueta().equals(etiqueta))
                .findFirst()
                .orElseThrow()
                .valor();
    }

    /** Un registro con una sola app, para poder probar la imputación. */
    private RegistroConectores registroDePrueba() {
        ConectorApp vbstats = new ConectorApp() {
            @Override
            public DescriptorApp descriptor() {
                return new DescriptorApp("vbstats", "VBStats", "Estadísticas de voleibol",
                        "#2196f3", "grafica", List.of("ios"), List.of(), List.of());
            }

            @Override
            public EstadoConector estado() {
                return EstadoConector.listo();
            }

            @Override
            public ResumenApp resumen(Rango rango) {
                // La contabilidad no pide nunca el resumen de una app: solo pregunta
                // al registro si un id existe.
                throw new UnsupportedOperationException();
            }
        };
        return new RegistroConectores(List.of(vbstats));
    }
}
