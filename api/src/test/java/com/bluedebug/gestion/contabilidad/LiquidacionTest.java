package com.bluedebug.gestion.contabilidad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El reparto entre los socios.
 *
 * Es la parte del módulo que produce una cifra que alguien va a mirar para
 * hacerle un Bizum a otro, así que es la que más merece una prueba. Un fallo
 * aquí no salta por ningún lado: sale un número creíble y equivocado.
 */
class LiquidacionTest {

    private static final List<String> SOCIOS = List.of("Adrián Estrada", "Diego Charro", "Rubén Rubio");

    @Test
    @DisplayName("quien paga de más queda con saldo a favor")
    void saldoAFavor() {
        Liquidacion liquidacion = Liquidacion.de(SOCIOS, Map.of(
                "Adrián Estrada", new BigDecimal("300.00"),
                "Diego Charro", BigDecimal.ZERO,
                "Rubén Rubio", BigDecimal.ZERO));

        assertEquals(new BigDecimal("300.00"), liquidacion.total());
        assertEquals(new BigDecimal("100.00"), liquidacion.porCabeza());
        assertEquals(new BigDecimal("200.00"), saldoDe(liquidacion, "Adrián Estrada"));
        assertEquals(new BigDecimal("-100.00"), saldoDe(liquidacion, "Diego Charro"));
    }

    @Test
    @DisplayName("los dos que deben le pasan el dinero al que puso todo")
    void ajustesConUnSoloPagador() {
        Liquidacion liquidacion = Liquidacion.de(SOCIOS, Map.of(
                "Adrián Estrada", new BigDecimal("300.00")));

        assertEquals(2, liquidacion.ajustes().size());
        assertTrue(liquidacion.ajustes().stream()
                .allMatch(a -> a.a().equals("Adrián Estrada")
                        && a.importe().compareTo(new BigDecimal("100.00")) == 0));
    }

    @Test
    @DisplayName("con las cuentas ya cuadradas no hay nada que transferir")
    void sinAjustesSiEstaEquilibrado() {
        Liquidacion liquidacion = Liquidacion.de(SOCIOS, Map.of(
                "Adrián Estrada", new BigDecimal("50.00"),
                "Diego Charro", new BigDecimal("50.00"),
                "Rubén Rubio", new BigDecimal("50.00")));

        assertTrue(liquidacion.ajustes().isEmpty());
    }

    @Test
    @DisplayName("un socio que no ha pagado nada sigue contando en el reparto")
    void elQueNoPagaTambienDebe() {
        Liquidacion liquidacion = Liquidacion.de(SOCIOS, Map.of(
                "Adrián Estrada", new BigDecimal("60.00"),
                "Diego Charro", new BigDecimal("60.00")));

        assertEquals(3, liquidacion.saldos().size());
        assertEquals(new BigDecimal("-40.00"), saldoDe(liquidacion, "Rubén Rubio"));
        // Rubén le debe 20 a cada uno de los otros dos.
        assertEquals(2, liquidacion.ajustes().size());
        assertTrue(liquidacion.ajustes().stream().allMatch(a -> a.de().equals("Rubén Rubio")));
    }

    @Test
    @DisplayName("lo que se pasa de un lado a otro suma lo mismo que se debe")
    void losAjustesCuadran() {
        Liquidacion liquidacion = Liquidacion.de(SOCIOS, Map.of(
                "Adrián Estrada", new BigDecimal("420.75"),
                "Diego Charro", new BigDecimal("99.00"),
                "Rubén Rubio", new BigDecimal("12.30")));

        BigDecimal transferido = liquidacion.ajustes().stream()
                .map(Liquidacion.Ajuste::importe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal aFavor = liquidacion.saldos().stream()
                .map(Liquidacion.Saldo::saldo)
                .filter(s -> s.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, transferido.compareTo(aFavor), transferido + " vs " + aFavor);
    }

    @Test
    @DisplayName("sin gastos no hay ni saldos ni transferencias")
    void periodoVacio() {
        Liquidacion liquidacion = Liquidacion.de(SOCIOS, Map.of());

        assertEquals(0, liquidacion.total().signum());
        assertTrue(liquidacion.ajustes().isEmpty());
        assertTrue(liquidacion.saldos().stream().allMatch(s -> s.saldo().signum() == 0));
    }

    private BigDecimal saldoDe(Liquidacion liquidacion, String socio) {
        return liquidacion.saldos().stream()
                .filter(s -> s.socio().equals(socio))
                .findFirst()
                .orElseThrow()
                .saldo();
    }
}
