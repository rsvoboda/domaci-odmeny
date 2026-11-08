package cz.domaci.odmeny.web;

import cz.domaci.odmeny.shared.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyFormatTest {

    @Test
    void formatsWholeCrowns() {
        assertEquals("50 Kč", MoneyFormat.format(Money.czk(50)));
        assertEquals("0 Kč", MoneyFormat.format(Money.ZERO));
    }

    @Test
    void formatsHalere() {
        assertEquals("12,50 Kč", MoneyFormat.format(Money.minor(1250)));
    }
}
