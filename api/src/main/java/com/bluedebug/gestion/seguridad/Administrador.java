package com.bluedebug.gestion.seguridad;

/**
 * Quién está usando el panel ahora mismo.
 *
 * @param email  su correo, que es la identidad real: es lo que está en la lista
 *               blanca y lo que se escribe en la auditoría de cada acción.
 * @param nombre nombre que dio Google, solo para saludar.
 * @param foto   url del avatar de Google.
 */
public record Administrador(String email, String nombre, String foto) {}
