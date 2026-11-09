package cz.domaci.odmeny.web;

import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.MoneyText;

/**
 * Web-facing money formatting. The implementation lives in {@code shared} so
 * non-web code (for example audit details) can use the same format.
 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    public static String format(Money money) {
        return MoneyText.format(money);
    }
}
