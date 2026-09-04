package com.bluedebug.gestion.contabilidad;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Dónde se guardan los gastos y quiénes los pagan.
 *
 * La base de datos es PROPIA DEL PANEL, y es la primera que lo es. Las de los
 * conectores son de las apps y se leen prestadas; esta la escribe solo esto. Por
 * eso aquí sí se crea la tabla sola al arrancar (ver {@link FuenteContabilidad}),
 * cosa impensable contra la de VBStats.
 *
 * Si no hay url, la sección se enseña apagada y explicando qué falta, igual que
 * un conector sin credenciales. El resto del panel no se entera.
 *
 * @param url     la base de datos. Vale la url de Railway tal cual, de Postgres
 *                ({@code postgresql://...}) o de MySQL ({@code mysql://...}), y
 *                también una de JDBC ya hecha.
 * @param usuario solo si la url es de tipo JDBC y no lleva credenciales dentro.
 * @param clave   íd.
 * @param socios  quiénes pueden figurar como pagadores. Es una lista cerrada a
 *                propósito: si fuese texto libre, «Adrián», «adrian» y «Adrian E.»
 *                serían tres personas distintas en el reparto y las cuentas no
 *                cuadrarían nunca.
 */
@ConfigurationProperties(prefix = "bluedebug.contabilidad")
public record PropiedadesContabilidad(String url, String usuario, String clave, List<String> socios) {

    public boolean hayBaseDeDatos() {
        return url != null && !url.isBlank();
    }

    /** Los socios sin blancos ni duplicados, en el orden en que se escribieron. */
    public List<String> sociosLimpios() {
        if (socios == null) {
            return List.of();
        }
        return socios.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
