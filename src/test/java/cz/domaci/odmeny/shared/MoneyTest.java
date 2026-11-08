package cz.domaci.odmeny.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void convertsCrownsToMinorUnits() {
        assertEquals(5000, Money.czk(50).minor());
        assertEquals(0, Money.ZERO.minor());
    }

    @Test
    void addsAndSubtractsExactAmounts() {
        assertEquals(Money.czk(75), Money.czk(50).plus(Money.czk(25)));
        assertEquals(Money.czk(25), Money.czk(50).minus(Money.czk(25)));
    }

    @Test
    void detectsNegativeAndZeroAmounts() {
        assertTrue(Money.czk(10).minus(Money.czk(20)).isNegative());
        assertTrue(Money.czk(10).minus(Money.czk(10)).isZero());
    }

    @Test
    void rejectsOverflowInsteadOfSilentlyWrapping() {
        assertThrows(ArithmeticException.class, () -> new Money(Long.MAX_VALUE).plus(Money.czk(1)));
    }
}
