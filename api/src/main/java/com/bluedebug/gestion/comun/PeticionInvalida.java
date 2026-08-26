package com.bluedebug.gestion.comun;

/** Lo que mandó el cliente no vale. Sale como un 400 con el motivo escrito. */
public class PeticionInvalida extends RuntimeException {

    public PeticionInvalida(String mensaje) {
        super(mensaje);
    }
}
