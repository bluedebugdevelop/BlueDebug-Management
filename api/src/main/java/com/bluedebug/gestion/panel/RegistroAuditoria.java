package com.bluedebug.gestion.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Qué se ha hecho desde el panel, quién y cuándo.
 *
 * DOS SITIOS, A PROPÓSITO. Cada apunte va al log del servidor con nivel WARN
 * —eso es lo que perdura, lo que se puede buscar meses después y lo que sobrevive
 * a un reinicio— y además se guarda en esta cola en memoria, que es solo para
 * poder enseñar «lo último que hiciste» en la pantalla sin ir a rebuscar en los
 * logs de Railway.
 *
 * Lo de memoria se pierde al reiniciar, y está bien que así sea: prometer un
 * historial permanente que en realidad se borra cada despliegue sería peor que no
 * tenerlo. Y se dice en la propia pantalla de Actividad.
 *
 * Desde que existe la contabilidad hay una base de datos del panel donde cabría
 * guardar esto. No se hace, y es deliberado: la auditoría cubre TODAS las apps y
 * atarla a una sección que puede no estar configurada dejaría sin registro al
 * resto. Si algún día se quiere permanente, el sitio donde enchufar la tabla es
 * este —y habría que quitar la nota de la pantalla, que dice lo contrario.
 */
@Component
public class RegistroAuditoria {

    private static final Logger log = LoggerFactory.getLogger(RegistroAuditoria.class);

    /** Cuántos apuntes se recuerdan. Suficiente para una sesión de trabajo. */
    private static final int TOPE = 200;

    private final Deque<Apunte> apuntes = new ArrayDeque<>();

    /**
     * @param momento cuándo.
     * @param admin   quién.
     * @param app     sobre qué aplicación.
     * @param accion  qué hizo.
     * @param detalle el resultado, en una línea.
     * @param correcto si salió bien.
     */
    public record Apunte(Instant momento, String admin, String app, String accion,
                         String detalle, boolean correcto) {}

    public synchronized void anotar(String admin, String app, String accion, String detalle, boolean correcto) {
        Apunte apunte = new Apunte(Instant.now(), admin, app, accion, detalle, correcto);

        apuntes.addFirst(apunte);
        while (apuntes.size() > TOPE) {
            apuntes.removeLast();
        }

        log.warn("AUDITORÍA: {} · {} · {} · {} · {}",
                admin, app, accion, correcto ? "ok" : "FALLÓ", detalle);
    }

    public synchronized List<Apunte> ultimos(int cuantos) {
        return apuntes.stream().limit(Math.max(1, cuantos)).toList();
    }
}
