package cz.domaci.odmeny.shared;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats integer minor units as Czech crowns, e.g. {@code 5000 -> "50 Kč"}.
 *
 * <p>Kept in the shared package so audit details can be formatted without a
 * dependency on the web layer.
 */
public final class MoneyText {

    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");

    private MoneyText() {
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
