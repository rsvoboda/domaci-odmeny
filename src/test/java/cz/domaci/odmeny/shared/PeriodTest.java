package cz.domaci.odmeny.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeriodTest {

    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    @Test
    void utcInstantAfterPragueMidnightBelongsToNextMonth() {
        assertEquals(Period.of(2026, 11), Period.from(Instant.parse("2026-10-31T23:30:00Z"), PRAGUE));
    }

    @Test
    void lastMomentOfMonthBelongsToThatMonth() {
        assertEquals(Period.of(2026, 11), Period.from(Instant.parse("2026-11-30T22:59:59Z"), PRAGUE));
    }

    @Test
    void firstMomentOfNextMonthBelongsToNextMonth() {
        assertEquals(Period.of(2026, 12), Period.from(Instant.parse("2026-11-30T23:00:00Z"), PRAGUE));
    }

    @Test
    void handlesDstSpringForward() {
        assertEquals(Period.of(2026, 3), Period.from(Instant.parse("2026-03-29T01:30:00Z"), PRAGUE));
    }

    @Test
    void handlesDstFallBack() {
        assertEquals(Period.of(2026, 10), Period.from(Instant.parse("2026-10-25T00:30:00Z"), PRAGUE));
    }

    @Test
    void nextAndPreviousCrossYearBoundary() {
        assertEquals(Period.of(2027, 1), Period.of(2026, 12).next());
        assertEquals(Period.of(2026, 12), Period.of(2027, 1).previous());
    }

    @Test
    void startAndEndUsePragueZone() {
        var start = Period.of(2026, 11).start(PRAGUE);
        assertEquals(1, start.getDayOfMonth());
        assertEquals(0, start.getHour());
        assertEquals(PRAGUE, start.getZone());
        assertEquals(Period.of(2026, 12).start(PRAGUE), Period.of(2026, 11).endExclusive(PRAGUE));
    }

    @Test
    void formatsStableKeyValue() {
        assertEquals("2026-11", Period.of(2026, 11).value());
        assertEquals("2026-11", Period.of(2026, 11).toString());
    }

    @Test
    void rejectsInvalidMonth() {
        assertThrows(IllegalArgumentException.class, () -> Period.of(2026, 13));
        assertThrows(IllegalArgumentException.class, () -> Period.of(2026, 0));
    }
}
