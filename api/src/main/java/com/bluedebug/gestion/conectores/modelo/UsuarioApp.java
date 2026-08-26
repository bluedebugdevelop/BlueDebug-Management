package com.bluedebug.gestion.conectores.modelo;

import java.time.Instant;
import java.util.Map;

/**
 * Una cuenta de usuario, vista desde el panel y ya normalizada.
 *
 * Cada app guarda a su gente de forma distinta —VBStats tiene filas en MySQL con
 * un plan de suscripción, CVO tiene documentos de Firestore con roles y equipos—
 * así que el mínimo común se declara aquí arriba y lo específico de cada una va
 * en {@code extra}, que la tabla del front pinta como columnas adicionales
 * usando {@code camposExtra} del descriptor de la app.
 *
 * @param id           identificador en su app de origen (número, uid, lo que sea).
 * @param nombre       nombre visible; puede venir vacío.
 * @param email        correo, la clave con la que se reconoce a alguien entre apps.
 * @param alta         cuándo se creó la cuenta.
 * @param ultimaSesion último acceso conocido. Null si la app no lo guarda.
 * @param plan         plan o rol resumido en una palabra ('free', 'pro', 'admin').
 * @param activo       si la cuenta está operativa (una baja en CVO no borra nada).
 * @param dispositivos cuántos móviles tiene registrados para notificaciones.
 * @param extra        campos propios de la app.
 */
public record UsuarioApp(
        String id,
        String nombre,
        String email,
        Instant alta,
        Instant ultimaSesion,
        String plan,
        boolean activo,
        int dispositivos,
        Map<String, Object> extra
) {}
