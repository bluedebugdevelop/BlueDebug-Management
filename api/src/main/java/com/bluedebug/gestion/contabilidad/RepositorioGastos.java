package com.bluedebug.gestion.contabilidad;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las cuatro operaciones sobre la tabla de gastos.
 *
 * Todo el SQL del paquete está aquí y en {@link FuenteContabilidad}, y está
 * escrito para que valga igual en Postgres que en MySQL: solo SELECT, INSERT,
 * UPDATE y DELETE con parámetros, sin una sola función propia de un motor. Por
 * eso el filtro por año se escribe con dos fechas y no con {@code YEAR(fecha)},
 * que en Postgres no existe.
 *
 * No hay agregados en SQL a propósito: los gastos de un año son unas decenas de
 * filas, así que se traen enteras y se suman en Java. Sale una sola consulta en
 * vez de cinco, y las sumas se hacen con BigDecimal en vez de con el DECIMAL del
 * motor de turno.
 */
@Repository
public class RepositorioGastos {

    private static final String COLUMNAS = """
            id, fecha, concepto, categoria, proveedor, importe, iva,
            pagado_por, app, recurrencia, nota, creado_por, creado_en
            """;

    private final FuenteContabilidad fuente;

    public RepositorioGastos(FuenteContabilidad fuente) {
        this.fuente = fuente;
    }

    /**
     * Los gastos de un año, del más reciente al más antiguo.
     *
     * @param anio el año, o 0 para traerlos todos.
     */
    public List<Gasto> delAnio(int anio) {
        if (anio <= 0) {
            return fuente.jdbc().query(
                    "SELECT " + COLUMNAS + " FROM gastos ORDER BY fecha DESC, creado_en DESC",
                    MAPEADOR);
        }

        LocalDate desde = LocalDate.of(anio, 1, 1);
        return fuente.jdbc().query(
                "SELECT " + COLUMNAS + " FROM gastos WHERE fecha >= ? AND fecha < ? "
                        + "ORDER BY fecha DESC, creado_en DESC",
                MAPEADOR,
                java.sql.Date.valueOf(desde),
                java.sql.Date.valueOf(desde.plusYears(1)));
    }

    public Optional<Gasto> buscar(String id) {
        return fuente.jdbc()
                .query("SELECT " + COLUMNAS + " FROM gastos WHERE id = ?", MAPEADOR, id)
                .stream()
                .findFirst();
    }

    /** El primer y el último año con algún apunte, para el selector de años. */
    public Optional<int[]> anosConDatos() {
        return fuente.jdbc().query(
                "SELECT MIN(fecha) AS primera, MAX(fecha) AS ultima FROM gastos",
                (rs, fila) -> {
                    java.sql.Date primera = rs.getDate("primera");
                    java.sql.Date ultima = rs.getDate("ultima");
                    if (primera == null || ultima == null) {
                        return null;
                    }
                    return new int[]{primera.toLocalDate().getYear(), ultima.toLocalDate().getYear()};
                })
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    public void insertar(Gasto gasto) {
        fuente.jdbc().update(
                "INSERT INTO gastos (" + COLUMNAS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                gasto.id(),
                java.sql.Date.valueOf(gasto.fecha()),
                gasto.concepto(),
                gasto.categoria().name(),
                gasto.proveedor(),
                gasto.importe(),
                gasto.iva(),
                gasto.pagadoPor(),
                gasto.app(),
                gasto.recurrencia().name(),
                gasto.nota(),
                gasto.creadoPor(),
                Timestamp.from(gasto.creadoEn()));
    }

    /**
     * Actualiza todo menos quién y cuándo lo apuntó.
     *
     * Esos dos se quedan como estaban a posta: son el rastro de quién metió la
     * fila, y reescribirlos al corregir una errata borraría justo lo que sirve
     * para preguntar después.
     */
    public void actualizar(Gasto gasto) {
        fuente.jdbc().update("""
                        UPDATE gastos SET fecha = ?, concepto = ?, categoria = ?, proveedor = ?,
                                          importe = ?, iva = ?, pagado_por = ?, app = ?,
                                          recurrencia = ?, nota = ?
                        WHERE id = ?
                        """,
                java.sql.Date.valueOf(gasto.fecha()),
                gasto.concepto(),
                gasto.categoria().name(),
                gasto.proveedor(),
                gasto.importe(),
                gasto.iva(),
                gasto.pagadoPor(),
                gasto.app(),
                gasto.recurrencia().name(),
                gasto.nota(),
                gasto.id());
    }

    public void borrar(String id) {
        fuente.jdbc().update("DELETE FROM gastos WHERE id = ?", id);
    }

    /**
     * Una categoría o una recurrencia que ya no exista en el enum no puede tirar
     * la pantalla entera: la fila sigue estando y el dinero sigue contando. Cae en
     * OTROS y en UNICO, que es lo más inocuo que se puede hacer con ella.
     */
    private static final RowMapper<Gasto> MAPEADOR = (ResultSet rs, int fila) -> new Gasto(
            rs.getString("id"),
            rs.getDate("fecha").toLocalDate(),
            rs.getString("concepto"),
            CategoriaGasto.de(rs.getString("categoria")).orElse(CategoriaGasto.OTROS),
            rs.getString("proveedor"),
            euros(rs, "importe"),
            euros(rs, "iva"),
            rs.getString("pagado_por"),
            rs.getString("app"),
            Recurrencia.de(rs.getString("recurrencia")).orElse(Recurrencia.UNICO),
            rs.getString("nota"),
            rs.getString("creado_por"),
            momento(rs));

    private static BigDecimal euros(ResultSet rs, String columna) throws SQLException {
        BigDecimal valor = rs.getBigDecimal(columna);
        return valor == null ? BigDecimal.ZERO : valor;
    }

    private static Instant momento(ResultSet rs) throws SQLException {
        Timestamp sello = rs.getTimestamp("creado_en");
        return sello == null ? Instant.EPOCH : sello.toInstant();
    }
}
