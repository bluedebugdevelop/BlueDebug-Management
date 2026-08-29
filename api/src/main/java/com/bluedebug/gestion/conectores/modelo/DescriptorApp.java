package com.bluedebug.gestion.conectores.modelo;

import java.util.List;

/**
 * La tarjeta de presentación de una app: lo que el panel necesita para pintarla
 * en el menú y decidir qué pestañas tiene, sin saber nada de cómo funciona por
 * dentro.
 *
 * @param id          identificador estable, en minúsculas. Va en las urls.
 * @param nombre      cómo se llama para un humano.
 * @param descripcion una línea, la que sale bajo el título.
 * @param color       color de acento en hex, para distinguirla de un vistazo.
 * @param icono       nombre del icono del set propio (ver {@code web/src/app/componentes/icono}).
 * @param plataformas 'ios', 'android', 'web'... solo informativo.
 * @param capacidades qué sabe hacer este conector. El front esconde las pestañas
 *                    que no estén declaradas, en vez de enseñar secciones vacías.
 * @param camposExtra columnas propias de esta app en la tabla de usuarios.
 */
public record DescriptorApp(
        String id,
        String nombre,
        String descripcion,
        String color,
        String icono,
        List<String> plataformas,
        List<Capacidad> capacidades,
        List<CampoExtra> camposExtra
) {
    public enum Capacidad {
        /** Sabe listar cuentas de usuario. */
        USUARIOS,
        /** Sabe dar series temporales de actividad (altas, sesiones, uso). */
        METRICAS,
        /** Sabe decir cuánto dinero ha entrado. */
        INGRESOS,
        /** Tiene acciones de administración ejecutables desde el panel. */
        ACCIONES,
        /** Permite dar de baja o borrar cuentas. */
        BORRAR_USUARIOS,
        /** Permite cambiar a mano el rol o el plan de una cuenta. */
        EDITAR_ROL
    }

    /** Una columna extra de la tabla de usuarios, sacada del mapa {@code extra}. */
    public record CampoExtra(String clave, String etiqueta) {}

    public boolean puede(Capacidad c) {
        return capacidades != null && capacidades.contains(c);
    }
}
