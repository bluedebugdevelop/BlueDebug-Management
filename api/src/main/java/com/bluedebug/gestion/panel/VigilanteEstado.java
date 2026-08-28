package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.RegistroConectores;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mira cada cierto tiempo si las aplicaciones responden, y guarda la respuesta.
 *
 * POR QUÉ EXISTE ESTO, que es una historia que merece quedar escrita.
 *
 * Comprobar el estado de una app no es gratis: {@code estado()} abre una conexión
 * a MySQL y hace una consulta a Firestore. Al principio eso se llamaba en
 * caliente desde dos sitios, y uno de ellos era {@code /api/salud} — el
 * healthcheck que consulta Railway cada pocos segundos para decidir si el
 * servicio está vivo.
 *
 * Mientras no hubo credenciales no se notó: sin configurar, {@code estado()}
 * contesta al instante. El día que se configuraron, cada latido del healthcheck
 * pasó a depender de dos servicios externos, y la sonda de Firestore además no
 * tenía tiempo límite. Bastaba que una de las dos tardara para que el healthcheck
 * no contestara, Railway diera el despliegue por caído y el dominio entero
 * devolviera 502. Es decir: una lentitud pasajera en una base de datos ajena
 * tiraba el panel completo.
 *
 * Un healthcheck tiene que contestar a «¿está este proceso vivo y sirviendo?», y
 * a nada más. Si además contesta por sus dependencias, deja de ser un healthcheck
 * y se convierte en un único punto de fallo que las agrupa todas.
 *
 * Así que las sondas se hacen aquí, en segundo plano, y quien quiera saber el
 * estado lee la última foto. Nunca se bloquea nadie esperando.
 */
@Component
public class VigilanteEstado {

    private static final Logger log = LoggerFactory.getLogger(VigilanteEstado.class);

    /** Cada cuánto se vuelve a mirar. */
    private static final long CADA_MS = 30_000;

    private final RegistroConectores registro;
    private final Map<String, EstadoConector> foto = new ConcurrentHashMap<>();

    public VigilanteEstado(RegistroConectores registro) {
        this.registro = registro;
    }

    /**
     * La primera pasada va con retraso a propósito.
     *
     * Arrancar el servidor y ponerse a hablar con dos bases de datos a la vez
     * retrasa el momento en que el puerto empieza a aceptar peticiones, que es
     * justo lo que el healthcheck está esperando. Primero se levanta, y a los
     * cinco segundos ya se mira hacia fuera.
     */
    @Scheduled(initialDelay = 5_000, fixedDelay = CADA_MS)
    public void revisar() {
        for (ConectorApp conector : registro.todos()) {
            EstadoConector estado;
            try {
                estado = conector.estado();
            } catch (Exception e) {
                log.warn("No se pudo comprobar el estado de {}: {}", conector.id(), e.getMessage());
                estado = EstadoConector.sinConfigurar("No se pudo comprobar. La traza está en el log.");
            }
            foto.put(conector.id(), estado);
        }
    }

    /**
     * El estado conocido de una app.
     *
     * Vacío mientras no se haya hecho la primera pasada: eso es «todavía no lo
     * sé», que no es lo mismo que «está caída», y quien lo lea debe distinguirlo.
     */
    public Optional<EstadoConector> de(String appId) {
        return Optional.ofNullable(foto.get(appId));
    }

    /** Cuántas responden, de las que ya se han podido mirar. */
    public long disponibles() {
        return foto.values().stream().filter(EstadoConector::disponible).count();
    }

    /** Si ya hay una primera foto de todas. */
    public boolean hayFoto() {
        return foto.size() >= registro.todos().size();
    }

    /**
     * Fuerza una revisión inmediata.
     *
     * La usa la pantalla de ajustes: al pulsar «volver a comprobar» hay que mirar
     * de verdad, no devolver la foto de hace medio minuto. Es una llamada
     * autenticada y con un humano esperando, así que aquí sí vale bloquear.
     */
    public void revisarAhora() {
        revisar();
    }
}
