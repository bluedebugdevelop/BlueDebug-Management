package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.seguridad.Administrador;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * La pantalla de inicio: todas las apps a la vez.
 */
@RestController
@RequestMapping("/api/panel")
public class ControladorPanel {

    /**
     * Los periodos que ofrece el selector. Cerrado a propósito: sin un tope,
     * cualquiera podría pedir «los últimos 10.000 días» y poner a leer la base de
     * datos de producción de VBStats entera para pintar una gráfica.
     */
    private static final List<Integer> PERIODOS = List.of(7, 30, 90, 365);

    private final ServicioPanel panel;
    private final RegistroAuditoria auditoria;
    private final VigilanteEstado vigilante;

    public ControladorPanel(ServicioPanel panel, RegistroAuditoria auditoria, VigilanteEstado vigilante) {
        this.panel = panel;
        this.auditoria = auditoria;
        this.vigilante = vigilante;
    }

    @GetMapping("/resumen")
    public ServicioPanel.PanelGeneral resumen(@RequestParam(defaultValue = "30") int dias) {
        return panel.general(rango(dias));
    }

    @GetMapping("/usuarios")
    public List<ServicioPanel.UsuarioGlobal> usuarios() {
        return panel.todosLosUsuarios();
    }

    @GetMapping("/auditoria")
    public List<RegistroAuditoria.Apunte> auditoria(@RequestParam(defaultValue = "50") int cuantos) {
        return auditoria.ultimos(Math.min(cuantos, 200));
    }

    /**
     * Lo que el front necesita para pintarse: quién ha entrado, qué apps hay y qué
     * periodos se pueden pedir. Una sola llamada al arrancar en vez de tres.
     */
    @GetMapping("/arranque")
    public Map<String, Object> arranque(@AuthenticationPrincipal Administrador admin,
                                        @RequestParam(defaultValue = "false") boolean revisar) {
        // Normalmente vale la foto que toma el vigilante en segundo plano. Con
        // `revisar=true` se sondea en vivo: es lo que pulsa el botón «volver a
        // comprobar» de Ajustes, donde devolver una foto de hace medio minuto
        // sería justo lo contrario de lo que se ha pedido.
        if (revisar) {
            vigilante.revisarAhora();
        }

        return Map.of(
                "admin", admin,
                "apps", panel.menu(),
                "periodos", PERIODOS);
    }

    static Rango rango(int dias) {
        if (!PERIODOS.contains(dias)) {
            throw new PeticionInvalida("El periodo tiene que ser uno de " + PERIODOS);
        }
        return Rango.ultimosDias(dias);
    }
}
