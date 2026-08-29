package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.comun.PeticionInvalida;
import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.RegistroConectores;
import com.bluedebug.gestion.conectores.modelo.AccionAdmin;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.Ingresos;
import com.bluedebug.gestion.conectores.modelo.ResultadoAccion;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import com.bluedebug.gestion.conectores.modelo.Tabla;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;
import com.bluedebug.gestion.seguridad.Administrador;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.Map;

/**
 * Todo lo que se puede hacer sobre UNA aplicación.
 *
 * Fíjate en que no hay ni una sola mención a VBStats ni a CVO en todo el fichero.
 * Es intencionado: este controlador vale igual para la app número siete, y por eso
 * añadir una no obliga a tocar nada de aquí.
 */
@RestController
@RequestMapping("/api/apps")
public class ControladorApps {

    private final RegistroConectores registro;
    private final ServicioPanel panel;
    private final ResumenesCacheados cache;
    private final RegistroAuditoria auditoria;

    public ControladorApps(RegistroConectores registro,
                           ServicioPanel panel,
                           ResumenesCacheados cache,
                           RegistroAuditoria auditoria) {
        this.registro = registro;
        this.panel = panel;
        this.cache = cache;
        this.auditoria = auditoria;
    }

    @GetMapping
    public List<ServicioPanel.AppEnMenu> apps() {
        return panel.menu();
    }

    @GetMapping("/{id}")
    public ResumenApp resumen(@PathVariable String id, @RequestParam(defaultValue = "30") int dias) {
        return panel.resumen(id, ControladorPanel.rango(dias));
    }

    @GetMapping("/{id}/usuarios")
    public List<UsuarioApp> usuarios(@PathVariable String id) {
        ConectorApp conector = registro.buscar(id);
        exigir(conector, DescriptorApp.Capacidad.USUARIOS, "no sabe listar usuarios");
        return conector.usuarios();
    }

    @GetMapping("/{id}/ingresos")
    public ResponseEntity<Ingresos> ingresos(@PathVariable String id, @RequestParam(defaultValue = "30") int dias) {
        ConectorApp conector = registro.buscar(id);
        exigir(conector, DescriptorApp.Capacidad.INGRESOS, "no tiene ingresos que enseñar");

        return panel.ingresos(id, ControladorPanel.rango(dias))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}/acciones")
    public List<AccionAdmin> acciones(@PathVariable String id) {
        return registro.buscar(id).acciones();
    }

    @GetMapping("/{id}/tablas")
    public List<Tabla> tablas(@PathVariable String id) {
        return registro.buscar(id).tablas();
    }

    /**
     * Ejecuta una acción.
     *
     * Todo lo que pasa por aquí queda anotado en la auditoría, salga bien o mal.
     * Los fallos son justo los que hay que poder mirar después: un aviso que no
     * llegó deja rastro aquí aunque el conector devolviera un error controlado.
     */
    @PostMapping("/{id}/acciones/{accionId}")
    public ResultadoAccion ejecutar(@PathVariable String id,
                                    @PathVariable String accionId,
                                    @RequestBody(required = false) Map<String, Object> parametros,
                                    @AuthenticationPrincipal Administrador admin) {

        ConectorApp conector = registro.buscar(id);

        boolean existe = conector.acciones().stream().anyMatch(a -> a.id().equals(accionId));
        if (!existe) {
            throw new PeticionInvalida(
                    conector.descriptor().nombre() + " no tiene ninguna acción llamada '" + accionId + "'");
        }

        ResultadoAccion resultado;
        try {
            resultado = conector.ejecutar(
                    accionId, parametros == null ? Map.of() : parametros, admin.email());
        } catch (RuntimeException e) {
            auditoria.anotar(admin.email(), id, accionId, e.getMessage(), false);
            throw e;
        }

        auditoria.anotar(admin.email(), id, accionId, resultado.mensaje(), resultado.correcto());
        cache.olvidar();
        return resultado;
    }

    /** Cómo se editan los roles en esta app, para que el front pinte el control. */
    @GetMapping("/{id}/edicion-rol")
    public ResponseEntity<com.bluedebug.gestion.conectores.modelo.EdicionRol> edicionRol(@PathVariable String id) {
        return registro.buscar(id).edicionRol()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record PeticionRol(List<String> roles) {}

    /**
     * Cambia el rol o el plan de una cuenta.
     *
     * Siempre recibe una lista, aunque la app solo admita un valor: así este
     * método vale igual para el plan único de VBStats y para los roles múltiples
     * del club, y el conector es quien valida lo que le encaja.
     */
    @PutMapping("/{id}/usuarios/{usuarioId}/rol")
    public ResultadoAccion cambiarRol(@PathVariable String id,
                                      @PathVariable String usuarioId,
                                      @RequestBody PeticionRol peticion,
                                      @AuthenticationPrincipal Administrador admin) {

        ConectorApp conector = registro.buscar(id);
        exigir(conector, DescriptorApp.Capacidad.EDITAR_ROL, "no permite cambiar roles desde el panel");

        List<String> roles = peticion == null || peticion.roles() == null ? List.of() : peticion.roles();
        String apunte = "cambiar-rol:" + usuarioId;

        ResultadoAccion resultado;
        try {
            resultado = conector.cambiarRol(usuarioId, roles, admin.email());
        } catch (RuntimeException e) {
            auditoria.anotar(admin.email(), id, apunte, e.getMessage(), false);
            throw e;
        }

        auditoria.anotar(admin.email(), id, apunte, resultado.mensaje(), resultado.correcto());
        cache.olvidar();
        return resultado;
    }

    /**
     * Borra —o da de baja, según lo que signifique en esa app— una cuenta.
     *
     * Lo que hace exactamente lo decide el conector: en VBStats es un borrado con
     * todos sus datos, y en el club es una baja reversible. La diferencia se
     * explica en la pantalla antes de pedir la confirmación, para que nadie pulse
     * esperando lo contrario de lo que va a pasar.
     */
    @DeleteMapping("/{id}/usuarios/{usuarioId}")
    public ResultadoAccion borrar(@PathVariable String id,
                                  @PathVariable String usuarioId,
                                  @AuthenticationPrincipal Administrador admin) {

        ConectorApp conector = registro.buscar(id);

        ResultadoAccion resultado;
        try {
            resultado = conector.borrarUsuario(usuarioId, admin.email());
        } catch (RuntimeException e) {
            auditoria.anotar(admin.email(), id, "borrar-usuario:" + usuarioId, e.getMessage(), false);
            throw e;
        }

        auditoria.anotar(admin.email(), id, "borrar-usuario:" + usuarioId,
                resultado.mensaje(), resultado.correcto());
        cache.olvidar();
        return resultado;
    }

    private void exigir(ConectorApp conector, DescriptorApp.Capacidad capacidad, String queja) {
        if (!conector.descriptor().puede(capacidad)) {
            throw new PeticionInvalida(conector.descriptor().nombre() + " " + queja);
        }
    }
}
