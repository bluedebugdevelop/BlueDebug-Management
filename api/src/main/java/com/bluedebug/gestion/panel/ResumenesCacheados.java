package com.bluedebug.gestion.panel;

import com.bluedebug.gestion.conectores.ConectorApp;
import com.bluedebug.gestion.conectores.RegistroConectores;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import com.bluedebug.gestion.conectores.modelo.Ingresos;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Los resúmenes de cada app, guardados un rato.
 *
 * ESTÁ EN SU PROPIA CLASE POR UN MOTIVO TÉCNICO, no por gusto. La caché de Spring
 * funciona con un proxy alrededor del bean: si {@code ServicioPanel} se llamara a
 * sí mismo un método {@code @Cacheable}, la llamada iría directa al objeto, se
 * saltaría el proxy y la caché no haría absolutamente nada —sin error, sin aviso,
 * simplemente sin caché—. Al vivir en otro bean, la llamada pasa por el proxy y
 * funciona.
 *
 * Cada resumen abre conexiones a MySQL, lee colecciones enteras de Firestore y
 * llama a Stripe. Sin esto, cuatro apps son cuatro rondas de eso por cada F5. Un
 * minuto de vida (configurado en {@code application.yml}) es el punto razonable:
 * un panel de administración no necesita el segundo exacto y, a cambio, se puede
 * recargar sin castigar a las bases de datos de producción.
 *
 * Los resultados en mal estado NO se guardan: una app caída se reintenta en la
 * siguiente carga, para que arreglar unas credenciales se note al momento y no al
 * minuto.
 */
@Service
public class ResumenesCacheados {

    private static final Logger log = LoggerFactory.getLogger(ResumenesCacheados.class);

    private final RegistroConectores registro;

    public ResumenesCacheados(RegistroConectores registro) {
        this.registro = registro;
    }

    @Cacheable(value = "resumenes", key = "#id + '|' + #rango.desde() + '|' + #rango.hasta()",
            unless = "#result == null || !#result.estado().disponible()")
    public ResumenApp resumen(String id, Rango rango) {
        ConectorApp conector = registro.buscar(id);
        try {
            return conector.resumen(rango);
        } catch (Exception e) {
            // Una app que revienta no puede tumbar la pantalla: se marca como caída y
            // el resto del panel sigue funcionando.
            log.error("Falló el resumen de {}", id, e);
            return ResumenApp.noDisponible(conector.descriptor(),
                    EstadoConector.sinConfigurar("Falló al leer sus datos. La traza está en el log."));
        }
    }

    @Cacheable(value = "ingresos", key = "#id + '|' + #rango.desde() + '|' + #rango.hasta()")
    public Optional<Ingresos> ingresos(String id, Rango rango) {
        ConectorApp conector = registro.buscar(id);
        try {
            return conector.ingresos(rango);
        } catch (Exception e) {
            log.error("Fallaron los ingresos de {}", id, e);
            return Optional.empty();
        }
    }

    /**
     * Tira la caché entera.
     *
     * Se llama después de cada acción que cambia algo: mandar un aviso o dar una
     * baja tiene que verse reflejado en la pantalla al volver, no un minuto
     * después. Se vacía todo y no solo la app tocada porque el panel general suma
     * varias, y dejar la mitad de las cifras viejas sería peor que recalcularlas.
     */
    @CacheEvict(value = {"resumenes", "ingresos"}, allEntries = true)
    public void olvidar() {
        log.debug("Caché de resúmenes vaciada tras una acción");
    }
}
