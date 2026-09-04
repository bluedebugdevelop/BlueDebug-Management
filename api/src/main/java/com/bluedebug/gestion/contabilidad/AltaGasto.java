package com.bluedebug.gestion.contabilidad;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que manda el formulario al dar de alta o editar un gasto.
 *
 * Es un record aparte de {@link Gasto} y no el mismo por una razón de seguridad
 * pequeña pero real: si el cuerpo de la petición se pegara directamente contra
 * {@code Gasto}, el navegador podría mandar {@code id}, {@code creadoPor} o
 * {@code creadoEn} y falsear quién apuntó qué. Aquí esos tres campos ni existen;
 * los pone el servidor.
 *
 * Los enum llegan como texto y se validan en {@link ServicioContabilidad}, para
 * poder devolver «esa categoría no existe» en castellano en vez del 400 pelado
 * que suelta Jackson cuando no sabe convertir un valor.
 */
public record AltaGasto(
        LocalDate fecha,
        String concepto,
        String categoria,
        String proveedor,
        BigDecimal importe,
        BigDecimal iva,
        String pagadoPor,
        String app,
        String recurrencia,
        String nota
) {}
