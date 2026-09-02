package com.bluedebug.gestion.comun;

import java.util.List;

/**
 * Quien traduce de verdad.
 *
 * Hay dos, y la razón de que exista la interfaz es poder tener uno gratis y uno
 * de pago sin que el resto del panel se entere de cuál está puesto: el que se
 * usa lo decide {@link ServicioTraduccion} mirando qué claves hay configuradas.
 *
 * Lo que un motor tiene que hacer es poco y muy concreto: mandar el texto y
 * devolver tres listas de líneas. Todo lo demás —repartir en líneas, comprobar
 * que el número cuadra, decidir qué se publica cuando no— vive fuera, porque son
 * reglas del panel y no del proveedor.
 */
public interface MotorTraduccion {

    /**
     * Las líneas traducidas, una lista por idioma y en el mismo orden que
     * entraron. Los tres campos son obligatorios en el esquema que se le pide al
     * modelo, pero puede llegar cualquiera vacío o a medias: de eso se ocupa
     * {@link ServicioTraduccion#recoger}.
     */
    record Traduccion(List<String> en, List<String> fr, List<String> pt) {
    }

    /** Si tiene credenciales para trabajar. */
    boolean configurado();

    /** Cómo se llama, para poder decirlo en los mensajes y en los logs. */
    String nombre();

    /**
     * Traduce.
     *
     * @param sistema  las reglas: qué es esto, cuántas líneas, cuánto ocupan.
     * @param peticion las líneas numeradas.
     * @throws RuntimeException si el proveedor falla. Lo recoge
     *                          {@link ServicioTraduccion}, que nunca deja que un
     *                          fallo de red tumbe una publicación.
     */
    Traduccion traducir(String sistema, String peticion);
}
