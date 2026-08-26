package com.bluedebug.gestion.conectores.modelo;

import java.util.List;

/**
 * La foto de una app: sus números grandes, sus gráficas y sus repartos.
 *
 * Es lo que se pinta al entrar en la app desde el menú, y también lo que el
 * panel general resume cuando junta todas.
 *
 * @param app      quién es.
 * @param estado   si el conector pudo hablar con su fuente de datos.
 * @param metricas las tarjetas de arriba.
 * @param series   las líneas de las gráficas, ya con todos los días.
 * @param repartos los donuts.
 */
public record ResumenApp(
        DescriptorApp app,
        EstadoConector estado,
        List<Metrica> metricas,
        List<Serie> series,
        List<Reparto> repartos
) {
    /** El resumen de una app a la que le faltan credenciales: se enseña igual, apagada. */
    public static ResumenApp noDisponible(DescriptorApp app, EstadoConector estado) {
        return new ResumenApp(app, estado, List.of(), List.of(), List.of());
    }
}
