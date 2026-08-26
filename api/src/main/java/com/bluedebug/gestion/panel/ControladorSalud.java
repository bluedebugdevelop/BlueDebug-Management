package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.conectores.RegistroConectores;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * El latido del servicio, para el healthcheck del despliegue.
 *
 * Va sin sesión —Railway no tiene cookie que enseñar— y por eso no dice nada que
 * no se pueda leer desde fuera: cuántas apps hay configuradas y cuántas
 * responden. Ni nombres, ni credenciales, ni cifras de negocio.
 */
@RestController
public class ControladorSalud {

    private final RegistroConectores registro;

    public ControladorSalud(RegistroConectores registro) {
        this.registro = registro;
    }

    @GetMapping("/api/salud")
    public Map<String, Object> salud() {
        return Map.of(
                "ok", true,
                "apps", registro.todos().size(),
                "disponibles", registro.disponibles().size());
    }
}
