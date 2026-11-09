package cz.domaci.odmeny.web;

import cz.domaci.odmeny.shared.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyParserTest {

    @Test
    void parsesWholeCrowns() {
        assertEquals(Money.czk(50), MoneyParser.parse("50"));
        assertEquals(Money.czk(0), MoneyParser.parse("0"));
    }

    @Test
    void parsesCommaAndDotDecimals() {
        assertEquals(Money.minor(5050), MoneyParser.parse("50,50"));
        assertEquals(Money.minor(5050), MoneyParser.parse("50.50"));
        assertEquals(Money.minor(5005), MoneyParser.parse("50,05"));
        assertEquals(Money.minor(50), MoneyParser.parse("0,50"));
    }

    @Test
    void parsesSignsAndSuffix() {
        assertEquals(Money.minor(-2050), MoneyParser.parse("-20,50"));
        assertEquals(Money.czk(20), MoneyParser.parse("+20 Kč"));
        assertEquals(Money.czk(20), MoneyParser.parse("20kc"));
    }

    @Test
    void ignoresSpaces() {
        assertEquals(Money.czk(1200), MoneyParser.parse(" 1 200 Kč "));
        assertEquals(Money.czk(1200), MoneyParser.parse("1\u00a0200"));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("10,123"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("Kč"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("-"));
    }

    @Test
    void capsTheAmount() {
        assertEquals(Money.czk(100_000), MoneyParser.parse("100000"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("100000,01"));
        assertThrows(IllegalArgumentException.class, () -> MoneyParser.parse("-100000,01"));
    }
}
