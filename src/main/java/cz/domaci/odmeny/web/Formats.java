package cz.domaci.odmeny.web;

import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Presentation formatting. Domain objects keep raw values; templates receive
 * already formatted strings.
 */
public final class Formats {

    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d. M. yyyy HH:mm", CZECH);
    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("LLLL yyyy", CZECH);

    private Formats() {
    }

    public static String money(Money money) {
        return MoneyFormat.format(money);
    }

    public static String dateTime(Instant instant, ZoneId zone) {
        return DATE_TIME.format(instant.atZone(zone));
    }

    public static String period(Period period, ZoneId zone) {
        String text = MONTH.format(period.start(zone));
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
