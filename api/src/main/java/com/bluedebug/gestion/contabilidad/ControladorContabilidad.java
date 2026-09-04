package com.bluedebug.gestion.contabilidad;

import com.bluedebug.gestion.panel.RegistroAuditoria;
import com.bluedebug.gestion.seguridad.Administrador;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los gastos de la empresa.
 *
 * Todo lo que escribe queda anotado en la auditoría, igual que las acciones sobre
 * las apps. Aquí importa incluso más: esto es dinero, y saber quién apuntó los
 * 300 € que nadie recuerda es media discusión ahorrada. El apunte lleva el
 * importe en el detalle para que se pueda reconstruir un borrado desde el log.
 */
@RestController
@RequestMapping("/api/contabilidad")
public class ControladorContabilidad {

    /** Cómo se llama esta sección en la pantalla de Actividad. */
    private static final String AMBITO = "Contabilidad";

    private final ServicioContabilidad contabilidad;
    private final RegistroAuditoria auditoria;

    public ControladorContabilidad(ServicioContabilidad contabilidad, RegistroAuditoria auditoria) {
        this.contabilidad = contabilidad;
        this.auditoria = auditoria;
    }

    /** Socios, categorías, apps y años: lo que el formulario necesita para pintarse. */
    @GetMapping("/arranque")
    public ServicioContabilidad.Catalogo arranque() {
        return contabilidad.catalogo();
    }

    /**
     * @param anio el año que se está mirando. Un 0 los trae todos.
     */
    @GetMapping("/resumen")
    public ServicioContabilidad.ResumenContabilidad resumen(@RequestParam(defaultValue = "0") int anio) {
        return contabilidad.resumen(anio);
    }

    @PostMapping("/gastos")
    public Gasto crear(@RequestBody AltaGasto alta, @AuthenticationPrincipal Administrador admin) {
        Gasto gasto = contabilidad.crear(alta, admin.email());
        anotar(admin, "alta-gasto", gasto);
        return gasto;
    }

    @PutMapping("/gastos/{id}")
    public Gasto editar(@PathVariable String id,
                        @RequestBody AltaGasto alta,
                        @AuthenticationPrincipal Administrador admin) {
        Gasto gasto = contabilidad.editar(id, alta);
        anotar(admin, "editar-gasto", gasto);
        return gasto;
    }

    @DeleteMapping("/gastos/{id}")
    public Gasto borrar(@PathVariable String id, @AuthenticationPrincipal Administrador admin) {
        Gasto gasto = contabilidad.borrar(id);
        anotar(admin, "borrar-gasto", gasto);
        return gasto;
    }

    private void anotar(Administrador admin, String accion, Gasto gasto) {
        // El importe se escribe como se escribe aquí —coma decimal— y no con el
        // toString() del BigDecimal: esta línea la lee una persona en la pantalla
        // de Actividad, no un programa.
        String importe = String.format(java.util.Locale.forLanguageTag("es-ES"), "%,.2f", gasto.importe());

        auditoria.anotar(admin.email(), AMBITO, accion,
                gasto.concepto() + " · " + importe + " € · lo pagó " + gasto.pagadoPor(),
                true);
    }
}
