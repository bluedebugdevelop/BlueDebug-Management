package com.bluedebug.gestion.conectores;

import com.bluedebug.gestion.conectores.modelo.AccionAdmin;
import com.bluedebug.gestion.conectores.modelo.DescriptorApp;
import com.bluedebug.gestion.conectores.modelo.EstadoConector;
import com.bluedebug.gestion.conectores.modelo.Ingresos;
import com.bluedebug.gestion.conectores.modelo.Rango;
import com.bluedebug.gestion.conectores.modelo.ResultadoAccion;
import com.bluedebug.gestion.conectores.modelo.ResumenApp;
import com.bluedebug.gestion.conectores.modelo.UsuarioApp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El contrato que cumple cada aplicación administrada.
 *
 * ESTA ES LA PIEZA QUE HACE QUE EL PANEL ESCALE. Para meter una app nueva:
 *
 *   1. Se crea una clase en {@code conectores/loquesea} anotada con {@code @Component}
 *      que implemente esta interfaz.
 *   2. Se declaran en su {@link #descriptor()} las capacidades que sabe cumplir.
 *
 * Y ya está. Spring la recoge sola, {@link RegistroConectores} la publica, y el
 * front la pinta en el menú con sus pestañas, su tabla de usuarios y sus botones
 * de acción sin que nadie escriba una línea de Angular.
 *
 * REGLAS DE LA CASA para quien escriba un conector:
 *
 *   - Nada de excepciones por falta de configuración. Si no hay credenciales,
 *     {@link #estado()} devuelve el motivo y los métodos devuelven vacío. Una app
 *     rota no puede tumbar el panel de las otras.
 *   - Solo se implementa lo que se declara. Si no está la capacidad USUARIOS,
 *     {@link #usuarios()} no se llama nunca; devolver lista vacía es correcto.
 *   - Las lecturas son de solo lectura de verdad. Todo lo que escribe pasa por
 *     {@link #ejecutar}, que es lo único que se audita.
 */
public interface ConectorApp {

    /** Quién es esta app y qué sabe hacer. */
    DescriptorApp descriptor();

    /**
     * Si ahora mismo puede hablar con su fuente de datos.
     *
     * Se comprueba de verdad —una consulta trivial, un ping— y no solo si las
     * variables de entorno están puestas: una contraseña mal copiada tiene que
     * salir en el panel como «no disponible», no como una pantalla en blanco.
     */
    EstadoConector estado();

    /** Los números, las gráficas y los repartos del periodo pedido. */
    ResumenApp resumen(Rango rango);

    /** Todas las cuentas. La paginación y el filtro los hace el front: son listas cortas. */
    default List<UsuarioApp> usuarios() {
        return List.of();
    }

    /** El dinero del periodo, si esta app cobra algo. */
    default Optional<Ingresos> ingresos(Rango rango) {
        return Optional.empty();
    }

    /** Lo que se puede hacer desde el panel sobre esta app. */
    default List<AccionAdmin> acciones() {
        return List.of();
    }

    /**
     * Tablas propias de esta app para su pestaña de detalle: el historial de
     * avisos de VBStats, los equipos del club... Ver {@link com.bluedebug.gestion.conectores.modelo.Tabla}
     * para el porqué de que sean genéricas.
     */
    default List<com.bluedebug.gestion.conectores.modelo.Tabla> tablas() {
        return List.of();
    }

    /**
     * Ejecuta una acción.
     *
     * @param accionId  el {@code id} de una de las {@link #acciones()}.
     * @param parametros lo que rellenó el administrador en el formulario.
     * @param emailAdmin quién la lanza, para dejarlo escrito donde la app lo guarde.
     */
    default ResultadoAccion ejecutar(String accionId, Map<String, Object> parametros, String emailAdmin) {
        return ResultadoAccion.error("Esta aplicación no tiene acciones");
    }

    /**
     * Propone valores para el formulario de una acción, sin ejecutarla.
     *
     * Solo se llama si la acción declaró un {@link AccionAdmin.Asistente}. No
     * escribe nada en ninguna parte —para eso está {@link #ejecutar}, que es lo
     * único que se audita—: devuelve texto que el front mete en los campos y que
     * quien está delante puede corregir antes de guardar.
     */
    default com.bluedebug.gestion.conectores.modelo.Sugerencia asistir(
            String accionId, Map<String, Object> parametros, String emailAdmin) {
        return com.bluedebug.gestion.conectores.modelo.Sugerencia.error(
                "Esta acción no tiene nada que rellenar sola");
    }

    /** Da de baja o borra una cuenta. Solo se llama si se declaró BORRAR_USUARIOS. */
    default ResultadoAccion borrarUsuario(String usuarioId, String emailAdmin) {
        return ResultadoAccion.error("Esta aplicación no permite borrar cuentas desde el panel");
    }

    /**
     * Cómo se editan los roles en esta app: qué valores hay y si admite varios.
     * Vacío si no se pueden editar desde el panel.
     */
    default Optional<com.bluedebug.gestion.conectores.modelo.EdicionRol> edicionRol() {
        return Optional.empty();
    }

    /**
     * Cambia el rol o el plan de una cuenta.
     *
     * Llega siempre una lista, aunque la app solo admita un valor: así el
     * controlador es el mismo para las dos formas y el conector decide qué hacer
     * con lo que recibe. Validar contra {@link #edicionRol()} es cosa suya.
     */
    default ResultadoAccion cambiarRol(String usuarioId, List<String> roles, String emailAdmin) {
        return ResultadoAccion.error("Esta aplicación no permite cambiar roles desde el panel");
    }

    /** Atajo: el id del descriptor, que es como se identifica en las urls. */
    default String id() {
        return descriptor().id();
    }
}
