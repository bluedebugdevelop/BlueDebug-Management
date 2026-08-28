package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.conectores.RegistroConectores;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * El latido del servicio, para el healthcheck del despliegue.
 *
 * NO TOCA NADA DE FUERA, y eso es el punto entero de este fichero. Contesta a
 * «¿está este proceso vivo y sirviendo peticiones?», que es lo único que un
 * healthcheck debe preguntar. Las cifras de disponibilidad salen de la última
 * foto que tomó {@link VigilanteEstado} en segundo plano; si aún no hay ninguna,
 * se dice, en vez de ponerse a mirar aquí mismo.
 *
 * La versión anterior consultaba MySQL y Firestore en cada latido. Con eso, una
 * lentitud en una base de datos ajena hacía que Railway diera el servicio por
 * caído y el dominio entero devolviera 502 — el panel se apagaba por un problema
 * que ni siquiera era suyo.
 *
 * Va sin sesión, así que no cuenta nada que no se pueda leer desde fuera:
 * cuántas apps hay configuradas y cuántas responden. Ni nombres, ni cifras de
 * negocio, ni motivos de error.
 */
@RestController
public class ControladorSalud {

    private final RegistroConectores registro;
    private final VigilanteEstado vigilante;

    public ControladorSalud(RegistroConectores registro, VigilanteEstado vigilante) {
        this.registro = registro;
        this.vigilante = vigilante;
    }

    @GetMapping("/api/salud")
    public Map<String, Object> salud() {
        Map<String, Object> salud = new LinkedHashMap<>();
        salud.put("ok", true);
        salud.put("apps", registro.todos().size());

        if (vigilante.hayFoto()) {
            salud.put("disponibles", vigilante.disponibles());
        } else {
            // Recién arrancado: todavía no se ha mirado. Decir 0 haría pensar que
            // están todas caídas.
            salud.put("disponibles", null);
            salud.put("comprobando", true);
        }

        return salud;
    }
}
