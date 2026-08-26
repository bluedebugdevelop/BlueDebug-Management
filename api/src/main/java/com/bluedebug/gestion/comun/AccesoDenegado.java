package com.bluedebug.gestion.comun;

/** Autenticado, pero sin permiso para esto. Sale como un 403. */
public class AccesoDenegado extends RuntimeException {

    public AccesoDenegado(String mensaje) {
        super(mensaje);
    }
}
