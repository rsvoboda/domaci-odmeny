package cz.domaci.odmeny.web;

import cz.domaci.odmeny.shared.Money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats integer minor units as Czech crowns, e.g. {@code 5000 -> "50 Kč"}.
 */
public final class MoneyFormat {

    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");

    private MoneyFormat() {
    }

    public static String format(Money money) {
        boolean hasHalere = money.minor() % 100 != 0;
        int fractionDigits = hasHalere ? 2 : 0;

        NumberFormat format = NumberFormat.getNumberInstance(CZECH);
        format.setMinimumFractionDigits(fractionDigits);
        format.setMaximumFractionDigits(fractionDigits);
        return format.format(BigDecimal.valueOf(money.minor(), 2)) + " Kč";
    }
}
