package cz.domaci.odmeny.shared;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * An accounting period: one calendar month in a given timezone.
 *
 * <p>All period calculations use {@code Europe/Prague}, so DST transitions and
 * month boundaries are resolved by {@link ZonedDateTime}.
 */
public record Period(int year, int month) implements Comparable<Period> {

    public Period {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12, was " + month);
        }
    }

    public static Period of(int year, int month) {
        return new Period(year, month);
    }

    public static Period from(Instant instant, ZoneId zone) {
        ZonedDateTime zoned = instant.atZone(zone);
        return new Period(zoned.getYear(), zoned.getMonthValue());
    }

    public static Period current(ZoneId zone) {
        return from(Instant.now(), zone);
    }

    /**
     * Parses the stable {@code yyyy-MM} form used in DynamoDB keys.
     */
    public static Period parse(String value) {
        if (value == null || value.length() != 7 || value.charAt(4) != '-') {
            throw new IllegalArgumentException("Invalid period: " + value);
        }
        int year = Integer.parseInt(value.substring(0, 4));
        int month = Integer.parseInt(value.substring(5, 7));
        return new Period(year, month);
    }

    public ZonedDateTime start(ZoneId zone) {
        return ZonedDateTime.of(year, month, 1, 0, 0, 0, 0, zone);
    }

    /**
     * Exclusive upper bound: the first instant of the next month.
     */
    public ZonedDateTime endExclusive(ZoneId zone) {
        return start(zone).plusMonths(1);
    }

    public boolean contains(Instant instant, ZoneId zone) {
        return from(instant, zone).equals(this);
    }

    public Period next() {
        return month == 12 ? new Period(year + 1, 1) : new Period(year, month + 1);
    }

    public Period previous() {
        return month == 1 ? new Period(year - 1, 12) : new Period(year, month - 1);
    }

    /**
     * Stable string form, for example {@code 2026-11}. Used in DynamoDB keys.
     */
    public String value() {
        return "%04d-%02d".formatted(year, month);
    }

    @Override
    public String toString() {
        return value();
    }

    @Override
    public int compareTo(Period other) {
        int byYear = Integer.compare(year, other.year);
        return byYear != 0 ? byYear : Integer.compare(month, other.month);
    }
}
