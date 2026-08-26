package com.bluedebug.gestion.comun;

/** Se pidió algo que no existe. Sale como un 404 limpio, no como un 500. */
public class NoEncontrado extends RuntimeException {

    public NoEncontrado(String mensaje) {
        super(mensaje);
    }
}
