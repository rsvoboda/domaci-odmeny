package cz.domaci.odmeny.household;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Household-level settings. One household per deployment in v1.
 *
 * @param currency ISO 4217 currency code; only CZK is supported in v1
 * @param timezone IANA timezone used for accounting periods
 */
public record Household(String id, String name, String currency, String timezone) {

    public Household {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(timezone, "timezone");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        // Fail early on an invalid zone instead of on the first period calculation.
        ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}
