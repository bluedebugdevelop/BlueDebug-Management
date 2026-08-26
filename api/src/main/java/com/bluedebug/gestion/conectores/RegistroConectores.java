package com.bluedebug.gestion.conectores;

import com.bluedebug.gestion.comun.NoEncontrado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La lista de aplicaciones que administra el panel.
 *
 * No hay ningún sitio donde estén enumeradas: Spring inyecta aquí todos los
 * beans que implementan {@link ConectorApp} y con eso se hace el índice. Añadir
 * una app es crear su clase; quitarla, borrarla. Este fichero no se toca nunca.
 */
@Component
public class RegistroConectores {

    private static final Logger log = LoggerFactory.getLogger(RegistroConectores.class);

    private final Map<String, ConectorApp> porId = new LinkedHashMap<>();

    public RegistroConectores(List<ConectorApp> conectores) {
        conectores.stream()
                .sorted(Comparator.comparing(c -> c.descriptor().nombre()))
                .forEach(c -> {
                    ConectorApp previo = porId.put(c.id(), c);
                    if (previo != null) {
                        // Dos conectores con el mismo id se pisarían en las urls y uno
                        // de los dos quedaría inalcanzable sin ningún aviso.
                        throw new IllegalStateException(
                                "Hay dos conectores con el id '" + c.id() + "': "
                                        + previo.getClass().getName() + " y " + c.getClass().getName());
                    }
                });

        log.info("Conectores registrados: {}", porId.keySet());
    }

    public List<ConectorApp> todos() {
        return List.copyOf(porId.values());
    }

    /** Los que ahora mismo pueden dar datos. */
    public List<ConectorApp> disponibles() {
        return porId.values().stream().filter(c -> c.estado().disponible()).toList();
    }

    public ConectorApp buscar(String id) {
        ConectorApp c = porId.get(id);
        if (c == null) {
            throw new NoEncontrado("No existe ninguna aplicación con el id '" + id + "'");
        }
        return c;
    }
}
