package com.bluedebug.gestion.contabilidad;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Quién ha puesto de más y quién de menos.
 *
 * Es la razón de ser de la columna «lo pagó»: apuntar el gasto sirve para saber
 * cuánto cuesta la empresa, pero apuntar quién lo pagó sirve para no acabar
 * discutiéndolo de memoria. Con los tres socios poniendo cosas a su tarjeta según
 * a quién le pillara, al mes siguiente nadie se acuerda de quién puso el dominio.
 *
 * El reparto es A PARTES IGUALES. No hay porcentajes de participación en ningún
 * sitio del panel y meterlos aquí sería inventarse una regla que nadie ha
 * acordado; el día que las participaciones no sean iguales, este es el único
 * fichero que hay que tocar.
 *
 * @param total     lo gastado en el periodo.
 * @param porCabeza lo que le tocaba poner a cada uno.
 * @param saldos    lo que puso cada uno y su diferencia con lo que le tocaba.
 * @param ajustes   las transferencias que dejarían la cuenta a cero.
 */
public record Liquidacion(
        BigDecimal total,
        BigDecimal porCabeza,
        List<Saldo> saldos,
        List<Ajuste> ajustes
) {
    /**
     * @param socio  quién.
     * @param pagado lo que ha puesto de su bolsillo.
     * @param saldo  pagado menos lo que le tocaba. En positivo, le deben; en
     *               negativo, debe.
     */
    public record Saldo(String socio, BigDecimal pagado, BigDecimal saldo) {}

    /** «Diego le pasa 40,50 € a Adrián». */
    public record Ajuste(String de, String a, BigDecimal importe) {}

    /**
     * Calcula el reparto a partir de lo que ha puesto cada socio.
     *
     * @param socios  todos los socios, incluidos los que no hayan pagado nada en el
     *                periodo: a esos les toca poner igual, y dejarlos fuera es
     *                justo lo que haría que las cuentas no cuadraran.
     * @param pagados lo que ha puesto cada uno.
     */
    public static Liquidacion de(List<String> socios, java.util.Map<String, BigDecimal> pagados) {
        if (socios.isEmpty()) {
            return new Liquidacion(BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of());
        }

        BigDecimal total = socios.stream()
                .map(s -> pagados.getOrDefault(s, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal porCabeza = total.divide(BigDecimal.valueOf(socios.size()), 2, RoundingMode.HALF_UP);

        List<Saldo> saldos = socios.stream()
                .map(socio -> {
                    BigDecimal puesto = pagados.getOrDefault(socio, BigDecimal.ZERO);
                    return new Saldo(socio, puesto, puesto.subtract(porCabeza));
                })
                .toList();

        return new Liquidacion(total, porCabeza, saldos, ajustes(saldos));
    }

    /**
     * Las transferencias que saldan las cuentas.
     *
     * El método es el de siempre: se empareja al que más debe con al que más le
     * deben, se pasa lo que quepa, y a repetir. Con tres socios salen dos
     * transferencias como mucho, que es el mínimo posible.
     *
     * El céntimo suelto que puede dejar el redondeo de {@code porCabeza} —dividir
     * 10 € entre 3 no da tres cifras exactas— se queda donde caiga: perseguirlo
     * complicaría esto para arreglar un céntimo que nadie va a reclamar. Por eso
     * se descartan también los ajustes de menos de un céntimo, que si no
     * aparecerían como «Rubén le pasa 0,00 € a Diego».
     */
    private static List<Ajuste> ajustes(List<Saldo> saldos) {
        List<Saldo> deben = new ArrayList<>(saldos.stream()
                .filter(s -> s.saldo().signum() < 0)
                .sorted(Comparator.comparing(Saldo::saldo))
                .toList());

        List<Saldo> lesDeben = new ArrayList<>(saldos.stream()
                .filter(s -> s.saldo().signum() > 0)
                .sorted(Comparator.comparing(Saldo::saldo).reversed())
                .toList());

        List<Ajuste> ajustes = new ArrayList<>();
        int i = 0;
        int j = 0;
        BigDecimal debe = BigDecimal.ZERO;
        BigDecimal leDeben = BigDecimal.ZERO;

        while (i < deben.size() && j < lesDeben.size()) {
            if (debe.signum() == 0) {
                debe = deben.get(i).saldo().negate();
            }
            if (leDeben.signum() == 0) {
                leDeben = lesDeben.get(j).saldo();
            }

            BigDecimal pasa = debe.min(leDeben);
            if (pasa.compareTo(new BigDecimal("0.01")) >= 0) {
                ajustes.add(new Ajuste(deben.get(i).socio(), lesDeben.get(j).socio(), pasa));
            }

            debe = debe.subtract(pasa);
            leDeben = leDeben.subtract(pasa);

            if (debe.signum() == 0) {
                i++;
            }
            if (leDeben.signum() == 0) {
                j++;
            }
        }

        return ajustes;
    }
}
