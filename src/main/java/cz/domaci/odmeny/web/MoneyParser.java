package cz.domaci.odmeny.web;

import cz.domaci.odmeny.shared.Money;

import java.util.Locale;

/**
 * Parses a parent-entered CZK amount into exact minor units.
 *
 * <p>Accepted forms: {@code 50}, {@code 50,50}, {@code 50.50}, {@code -20},
 * with an optional {@code Kč} suffix. At most two decimal places are allowed,
 * and the absolute value is capped at {@link #MAX}.
 */
public final class MoneyParser {

    public static final Money MAX = Money.czk(100_000);

    private MoneyParser() {
    }

    public static Money parse(String input) {
        if (input == null) {
            throw invalid();
        }

        String text = input.strip()
                .replace('\u00a0', ' ')
                .replace(" ", "");
        if (text.isEmpty()) {
            throw invalid();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.endsWith("kč")) {
            text = text.substring(0, text.length() - 2);
        } else if (lower.endsWith("kc")) {
            text = text.substring(0, text.length() - 2);
        }
        text = text.strip();
        if (text.isEmpty()) {
            throw invalid();
        }

        boolean negative = false;
        char first = text.charAt(0);
        if (first == '+' || first == '-') {
            negative = first == '-';
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            throw invalid();
        }

        int comma = text.indexOf(',');
        int dot = text.indexOf('.');
        if (comma >= 0 && dot >= 0) {
            throw invalid();
        }

        int separator = Math.max(comma, dot);
        String whole;
        String fraction;
        if (separator >= 0) {
            whole = text.substring(0, separator);
            fraction = text.substring(separator + 1);
            if (fraction.indexOf(',') >= 0 || fraction.indexOf('.') >= 0) {
                throw invalid();
            }
        } else {
            whole = text;
            fraction = "";
        }

        if (whole.isEmpty() && fraction.isEmpty()) {
            throw invalid();
        }
        if (!whole.isEmpty() && !isDigits(whole)) {
            throw invalid();
        }
        if (!fraction.isEmpty() && !isDigits(fraction)) {
            throw invalid();
        }
        if (fraction.length() > 2) {
            throw invalid();
        }

        try {
            long crowns = whole.isEmpty() ? 0 : Long.parseLong(whole);
            long halere = fraction.isEmpty() ? 0 : Long.parseLong((fraction + "00").substring(0, 2));
            long minor = Math.addExact(Math.multiplyExact(crowns, 100), halere);
            Money amount = Money.minor(negative ? Math.negateExact(minor) : minor);
            if (amount.compareTo(MAX) > 0 || amount.negate().compareTo(MAX) > 0) {
                throw invalid();
            }
            return amount;
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid();
        }
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid CZK amount");
    }
}
